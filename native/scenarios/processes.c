#define _GNU_SOURCE
#include "process.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %s:%d: %s (errno=%d)\n",__FILE__,__LINE__,#x,errno); exit(1); } } while (0)
static void nap(void) { struct timespec t={0,10000000}; nanosleep(&t,NULL); }
static void record(const char *path) {
    int fd=open(path,O_WRONLY|O_CREAT|O_APPEND,0600); CHECK(fd>=0);
    char b[32]; int n=snprintf(b,sizeof b,"%ld\n",(long)getpid());
    CHECK(write(fd,b,(size_t)n)==n); CHECK(!close(fd));
}
static void forever(void) { for (;;) pause(); }
static void fixture(const char *mode,const char *path) {
    if (!strcmp(mode,"exit")) exit(23);
    if (!strcmp(mode,"signal")) { raise(SIGUSR1); exit(1); }
    int ready[2]; CHECK(!pipe(ready));
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        close(ready[0]); CHECK(setsid()>0);
        signal(SIGTERM,SIG_IGN); record(path);
        pid_t grandchild=fork(); CHECK(grandchild>=0);
        if (!grandchild) { record(path); CHECK(write(ready[1],"g",1)==1); close(ready[1]); forever(); }
        CHECK(write(ready[1],"c",1)==1); close(ready[1]); forever();
    }
    close(ready[1]); char byte; unsigned count=0;
    while (read(ready[0],&byte,1)==1) count++;
    CHECK(count==2); close(ready[0]);
    record(path); /* last line proves descendants reached their ready points */
    if (!strcmp(mode,"tree-exit")) exit(0);
    signal(SIGTERM,SIG_IGN); forever();
}
static unsigned recorded(const char *path,int dead) {
    FILE *f=fopen(path,"r"); if (!f) return 0;
    long pid; unsigned count=0;
    while (fscanf(f,"%ld",&pid)==1) {
        CHECK(pid>1 && pid<=INT_MAX); count++;
        if (dead) { errno=0; CHECK(kill((pid_t)pid,0)==-1 && errno==ESRCH); }
    }
    fclose(f); return count;
}
static void await_ready(const char *path) {
    unsigned i; for(i=0;i<500 && recorded(path,0)!=3;i++) nap();
    CHECK(i<500);
}
static tp_result run(const char *self,const char *mode,const char *path,uint64_t timeout) {
    char *args[]={(char *)self,"--fixture",(char *)mode,(char *)path,NULL};
    tp_options options={timeout,20,2000}; tp_result r;
    CHECK(!tp_run(args,&options,&r)); CHECK(r.cleanup_complete && !r.error);
    return r;
}
int main(int argc,char **argv) {
    if(argc==4 && !strcmp(argv[1],"--fixture")) { fixture(argv[2],argv[3]); return 1; }
    CHECK(argc==2 && argv[0][0]=='/');
    CHECK(!mkdir(argv[1],0700) || errno==EEXIST);
    char path[PATH_MAX]; CHECK(snprintf(path,sizeof path,"%s/pids",argv[1])<(int)sizeof path);
    tp_result r=run(argv[0],"exit",path,2000);
    CHECK(r.exit_code==23 && !r.timed_out && r.reaped==1);
    sigset_t blocked,previous; sigemptyset(&blocked); sigaddset(&blocked,SIGUSR1);
    CHECK(!sigprocmask(SIG_BLOCK,&blocked,&previous));
    void (*old)(int)=signal(SIGUSR1,SIG_IGN); CHECK(old!=SIG_ERR);
    r=run(argv[0],"signal",path,2000); CHECK(r.term_signal==SIGUSR1 && r.exit_code==-1);
    CHECK(signal(SIGUSR1,old)!=SIG_ERR); CHECK(!sigprocmask(SIG_SETMASK,&previous,NULL));
    char *absent[]={"/no-such-trough-program",NULL}; tp_options options={1000,0,1000};
    CHECK(!tp_run(absent,&options,&r)); CHECK(r.exec_errno==ENOENT && r.exit_code==127 && r.cleanup_complete);
    options.timeout_ms=0; CHECK(tp_run(absent,&options,&r)==-1 && r.error==EINVAL);
    puts("ok normal/nonzero/signalled/exec failure outcomes and invalid deadline");
    for(unsigned i=0;i<12;i++) {
        unlink(path); r=run(argv[0],"tree-exit",path,3000);
        CHECK(!r.exit_code && r.reaped==3 && recorded(path,1)==3);
    }
    unlink(path); r=run(argv[0],"tree-wait",path,1000);
    CHECK(r.timed_out && r.reaped==3 && recorded(path,1)==3);
    puts("ok 12 normal-exit trees and deadline: new-session descendants killed and reaped");

    /* An existing child must cause rejection, never cleanup of unrelated work. */
    pid_t outsider=fork(); CHECK(outsider>=0); if(!outsider) forever();
    options.timeout_ms=1000;
    CHECK(tp_run(absent,&options,&r)==-1 && r.error==EBUSY); CHECK(!kill(outsider,0));
    /* The supervisor below is a sibling of outsider. proc fallback must not signal it. */
    for(unsigned i=0;i<3;i++) {
        unlink(path); int result[2]; CHECK(!pipe(result));
        pid_t supervisor=fork(); CHECK(supervisor>=0);
        if(!supervisor) {
            close(result[0]); tp_result got=run(argv[0],"tree-wait",path,10000);
            CHECK(write(result[1],&got,sizeof got)==sizeof got); close(result[1]); _exit(0);
        }
        close(result[1]); await_ready(path);
        int sig=i==0?SIGTERM:i==1?SIGINT:SIGHUP; CHECK(!kill(supervisor,sig));
        CHECK(read(result[0],&r,sizeof r)==sizeof r); close(result[0]);
        int status; CHECK(waitpid(supervisor,&status,0)==supervisor && WIFEXITED(status) && !WEXITSTATUS(status));
        CHECK(r.cancelled_signal==sig && !r.timed_out && r.reaped==3 && recorded(path,1)==3);
        CHECK(!kill(outsider,0));
    }
    CHECK(!kill(outsider,SIGKILL)); CHECK(waitpid(outsider,NULL,0)==outsider);
    puts("ok TERM/INT/HUP cancellation; existing children rejected; unrelated sibling survives");
    /* Root adopts the supervisor after its direct owner dies; the supervisor must still
     * clean its own tree and report parent-death cancellation before it exits. */
    CHECK(!prctl(PR_SET_CHILD_SUBREAPER,1L,0L,0L,0L));
    unlink(path); int result[2]; CHECK(!pipe(result));
    pid_t owner=fork(); CHECK(owner>=0);
    if(!owner) {
        close(result[0]); pid_t supervisor=fork(); CHECK(supervisor>=0);
        if(!supervisor) {
            tp_result got=run(argv[0],"tree-wait",path,10000);
            CHECK(write(result[1],&got,sizeof got)==sizeof got); close(result[1]); _exit(0);
        }
        close(result[1]); forever();
    }
    close(result[1]); await_ready(path); CHECK(!kill(owner,SIGKILL));
    CHECK(waitpid(owner,NULL,0)==owner);
    CHECK(read(result[0],&r,sizeof r)==sizeof r); close(result[0]);
    int status; CHECK(waitpid(-1,&status,0)>0 && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(r.cancelled_signal==SIGTERM && r.cleanup_complete && r.reaped==3 && recorded(path,1)==3);
    CHECK(waitpid(-1,NULL,WNOHANG)==-1 && errno==ECHILD);
    CHECK(!prctl(PR_SET_CHILD_SUBREAPER,0L,0L,0L,0L));
    puts("ok owner death cancels supervisor and reaps its escaped descendants");
    puts("core-processes: all checks passed (owned trusted subprocess cleanup, not a sandbox)");
    return 0;
}
