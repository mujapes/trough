/* getaddrinfo through Trough's glibc: proves /etc/resolv.conf -> /proc/self/fd/99 works. */
#include <netdb.h>
#include <stdio.h>
#include <arpa/inet.h>
#include <string.h>

int main(int argc, char **argv) {
    const char *host = argc > 1 ? argv[1] : "api.anthropic.com";
    struct addrinfo hints, *res, *p;
    memset(&hints, 0, sizeof hints);
    hints.ai_socktype = SOCK_STREAM;
    int rc = getaddrinfo(host, "443", &hints, &res);
    printf("{\"host\":\"%s\",\"rc\":%d,\"error\":\"%s\",\"addrs\":[", host, rc, rc ? gai_strerror(rc) : "");
    if (!rc) {
        int first = 1;
        for (p = res; p; p = p->ai_next) {
            char buf[64];
            void *a = p->ai_family == AF_INET ? (void *)&((struct sockaddr_in *)p->ai_addr)->sin_addr
                                              : (void *)&((struct sockaddr_in6 *)p->ai_addr)->sin6_addr;
            inet_ntop(p->ai_family, a, buf, sizeof buf);
            printf("%s\"%s\"", first ? "" : ",", buf);
            first = 0;
        }
        freeaddrinfo(res);
    }
    printf("]}\n");
    return rc != 0;
}
