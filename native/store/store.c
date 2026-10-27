#define _GNU_SOURCE
#include "store.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sqlite3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

enum { BATCH_LIMIT = 4096, SCHEMA_VERSION = 1 };

struct ts_store {
    sqlite3 *db;
    int lock, objects;           /* owner lock fd, objects directory fd */
    char *dir;
    uint64_t tmp_counter;
    size_t recovered_unknown;
    sqlite3_stmt *current, *revision, *at_revision, *insert_commit, *insert_revision,
                 *create, *update, *effect, *effect_insert, *effect_update, *effect_log;
};

static const char *MIGRATIONS[SCHEMA_VERSION] = {
    "CREATE TABLE commits(seq INTEGER PRIMARY KEY AUTOINCREMENT);"
    "CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, revision INTEGER NOT NULL,"
    "  schema INTEGER NOT NULL, live INTEGER NOT NULL);"
    "CREATE TABLE revisions(id INTEGER NOT NULL, revision INTEGER NOT NULL, schema INTEGER NOT NULL,"
    "  data BLOB NOT NULL, removed INTEGER NOT NULL, seq INTEGER NOT NULL,"
    "  PRIMARY KEY(id, revision)) WITHOUT ROWID;"
    "CREATE TABLE effects(intent BLOB PRIMARY KEY, kind INTEGER NOT NULL, state INTEGER NOT NULL,"
    "  request BLOB NOT NULL, outcome BLOB, seq INTEGER NOT NULL) WITHOUT ROWID;"
    "CREATE TABLE effect_log(intent BLOB NOT NULL, seq INTEGER NOT NULL, from_state INTEGER NOT NULL,"
    "  to_state INTEGER NOT NULL, PRIMARY KEY(intent, seq)) WITHOUT ROWID;",
};

static ts_status code(int rc) {
    switch (rc) {
    case SQLITE_OK: case SQLITE_DONE: case SQLITE_ROW: return TS_OK;
    case SQLITE_FULL: return TS_FULL;
    case SQLITE_CONSTRAINT: case SQLITE_MISMATCH: case SQLITE_TOOBIG: return TS_INVALID;
    default: return TS_IO;
    }
}

static int exec(ts_store *s, const char *sql) { return sqlite3_exec(s->db, sql, 0, 0, 0); }

static int step_done(sqlite3_stmt *st) {
    int rc = sqlite3_step(st);
    sqlite3_reset(st);
    return rc == SQLITE_DONE ? SQLITE_OK : rc;
}

/* ---- SHA-256 (FIPS 180-4) ---- */
static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void block(uint32_t h[8], const uint8_t *p) {
    uint32_t w[64], a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
    for (int i = 0; i < 16; i++)
        w[i] = (uint32_t)p[4*i] << 24 | (uint32_t)p[4*i+1] << 16 | (uint32_t)p[4*i+2] << 8 | p[4*i+3];
    for (int i = 16; i < 64; i++)
        w[i] = w[i-16] + (ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3)) + w[i-7] +
               (ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10));
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = k + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
        uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        k = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += k;
}

void ts_sha256(const void *data, size_t size, uint8_t out[32]) {
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    const uint8_t *p = data;
    size_t left = size;
    for (; left >= 64; left -= 64, p += 64) block(h, p);
    uint8_t tail[128] = {0};
    memcpy(tail, p, left);
    tail[left] = 0x80;
    size_t n = left < 56 ? 64 : 128;
    uint64_t bits = (uint64_t)size * 8;
    for (int i = 0; i < 8; i++) tail[n - 1 - i] = (uint8_t)(bits >> (8 * i));
    block(h, tail);
    if (n == 128) block(h, tail + 64);
    for (int i = 0; i < 8; i++) {
        out[4*i] = h[i] >> 24; out[4*i+1] = h[i] >> 16; out[4*i+2] = h[i] >> 8; out[4*i+3] = h[i];
    }
}

/* ---- open, migrate, recover ---- */
static int prepare(ts_store *s, sqlite3_stmt **st, const char *sql) {
    return sqlite3_prepare_v3(s->db, sql, -1, SQLITE_PREPARE_PERSISTENT, st, 0);
}

static int migrate(ts_store *s) {
    sqlite3_stmt *st;
    int rc = sqlite3_prepare_v2(s->db, "PRAGMA user_version", -1, &st, 0);
    if (rc) return rc;
    int version = sqlite3_step(st) == SQLITE_ROW ? sqlite3_column_int(st, 0) : -1;
    sqlite3_finalize(st);
    if (version < 0 || version > SCHEMA_VERSION) return SQLITE_CORRUPT;  /* newer store: never downgrade */
    for (; version < SCHEMA_VERSION; version++) {
        char bump[64];
        snprintf(bump, sizeof bump, "PRAGMA user_version=%d", version + 1);
        if ((rc = exec(s, "BEGIN IMMEDIATE")) || (rc = exec(s, MIGRATIONS[version])) ||
            (rc = exec(s, bump)) || (rc = exec(s, "COMMIT"))) { exec(s, "ROLLBACK"); return rc; }
    }
    return SQLITE_OK;
}

/* The previous owner is gone, so every issued effect's outcome is lost to us: unknown. */
static int recover(ts_store *s) {
    int rc;
    if ((rc = exec(s, "BEGIN IMMEDIATE"))) return rc;
    sqlite3_stmt *count;
    if ((rc = sqlite3_prepare_v2(s->db, "SELECT count(*) FROM effects WHERE state=?", -1, &count, 0))) goto fail;
    sqlite3_bind_int(count, 1, TS_ISSUED);
    sqlite3_step(count);
    s->recovered_unknown = (size_t)sqlite3_column_int64(count, 0);
    sqlite3_finalize(count);
    if (!s->recovered_unknown) return exec(s, "COMMIT");
    char sql[512];
    snprintf(sql, sizeof sql,
        "INSERT INTO commits DEFAULT VALUES;"
        "INSERT INTO effect_log SELECT intent, (SELECT max(seq) FROM commits), %d, %d FROM effects WHERE state=%d;"
        "UPDATE effects SET state=%d, seq=(SELECT max(seq) FROM commits) WHERE state=%d;",
        TS_ISSUED, TS_UNKNOWN, TS_ISSUED, TS_UNKNOWN, TS_ISSUED);
    if ((rc = exec(s, sql)) || (rc = exec(s, "COMMIT"))) goto fail;
    return SQLITE_OK;
fail:
    exec(s, "ROLLBACK");
    return rc;
}

static void sweep_tmp(ts_store *s) {   /* interrupted object writes never became visible */
    DIR *d = fdopendir(dup(s->objects));
    if (!d) return;
    for (struct dirent *e; (e = readdir(d));)
        if (!strncmp(e->d_name, "tmp-", 4)) unlinkat(s->objects, e->d_name, 0);
    closedir(d);
}

ts_status ts_open(const char *dir, ts_store **out) {
    *out = 0;
    if (!dir || !*dir) return TS_INVALID;
    ts_store *s = calloc(1, sizeof *s);
    if (!s) return TS_CAPACITY;
    s->lock = s->objects = -1;
    char path[4096];
    ts_status status = TS_IO;
    if (mkdir(dir, 0700) && errno != EEXIST) goto fail;
    if (!(s->dir = strdup(dir))) { status = TS_CAPACITY; goto fail; }
    snprintf(path, sizeof path, "%s/owner.lock", dir);
    if ((s->lock = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600)) < 0) goto fail;
    /* One owner: a second opener would otherwise mark the live owner's issued effects unknown. */
    if (flock(s->lock, LOCK_EX | LOCK_NB)) { status = errno == EWOULDBLOCK ? TS_CONFLICT : TS_IO; goto fail; }
    snprintf(path, sizeof path, "%s/objects", dir);
    if (mkdir(path, 0700) && errno != EEXIST) goto fail;
    if ((s->objects = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) goto fail;
    sweep_tmp(s);
    snprintf(path, sizeof path, "%s/records.db", dir);
    int rc = sqlite3_open_v2(path, &s->db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, 0);
    if (!rc) rc = exec(s, "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;");
    if (!rc) rc = migrate(s);
    if (!rc) rc = recover(s);
    if (!rc) rc = prepare(s, &s->current,
        "SELECT r.revision, r.schema, r.live, v.data, v.seq FROM records r "
        "JOIN revisions v ON v.id=r.id AND v.revision=r.revision WHERE r.id=?");
    if (!rc) rc = prepare(s, &s->revision, "SELECT revision, live FROM records WHERE id=?");
    if (!rc) rc = prepare(s, &s->at_revision,
        "SELECT schema, data, removed, seq FROM revisions WHERE id=? AND revision=?");
    if (!rc) rc = prepare(s, &s->insert_commit, "INSERT INTO commits DEFAULT VALUES");
    if (!rc) rc = prepare(s, &s->insert_revision,
        "INSERT INTO revisions(id, revision, schema, data, removed, seq) VALUES(?,?,?,?,?,?)");
    if (!rc) rc = prepare(s, &s->create, "INSERT INTO records(revision, schema, live) VALUES(1,?,1)");
    if (!rc) rc = prepare(s, &s->update, "UPDATE records SET revision=?, schema=?, live=? WHERE id=?");
    if (!rc) rc = prepare(s, &s->effect, "SELECT state, kind, request FROM effects WHERE intent=?");
    if (!rc) rc = prepare(s, &s->effect_insert,
        "INSERT INTO effects(intent, kind, state, request, seq) VALUES(?,?,?,?,?)");
    if (!rc) rc = prepare(s, &s->effect_update,
        "UPDATE effects SET state=?, outcome=coalesce(?, outcome), seq=? WHERE intent=?");
    if (!rc) rc = prepare(s, &s->effect_log,
        "INSERT INTO effect_log(intent, seq, from_state, to_state) VALUES(?,?,?,?)");
    if (rc) { status = rc == SQLITE_CORRUPT || rc == SQLITE_NOTADB ? TS_INVALID : code(rc); goto fail; }
    *out = s;
    return TS_OK;
fail:
    ts_close(s);
    return status;
}

void ts_close(ts_store *s) {
    if (!s) return;
    sqlite3_stmt *all[] = {s->current, s->revision, s->at_revision, s->insert_commit, s->insert_revision,
                           s->create, s->update, s->effect, s->effect_insert, s->effect_update, s->effect_log};
    for (size_t i = 0; i < sizeof all / sizeof *all; i++) sqlite3_finalize(all[i]);
    if (s->db) sqlite3_close(s->db);
    if (s->objects >= 0) close(s->objects);
    if (s->lock >= 0) close(s->lock);   /* releases the owner lock */
    free(s->dir);
    free(s);
}

ts_status ts_limit_pages(ts_store *s, uint64_t pages) {
    char sql[64];
    snprintf(sql, sizeof sql, "PRAGMA max_page_count=%llu", (unsigned long long)(pages ? pages : 4294967294ULL));
    return code(exec(s, sql));
}

size_t ts_recovered_unknown(const ts_store *s) { return s->recovered_unknown; }

/* ---- reads ---- */
static ts_status fill(sqlite3_stmt *st, int data_column, ts_record *r, void *buffer, size_t capacity) {
    const void *data = sqlite3_column_blob(st, data_column);
    r->size = (size_t)sqlite3_column_bytes(st, data_column);
    if (r->size > capacity) return TS_CAPACITY;
    if (r->size) memcpy(buffer, data, r->size);
    return TS_OK;
}

ts_status ts_get(ts_store *s, uint64_t id, ts_record *r, void *buffer, size_t capacity) {
    memset(r, 0, sizeof *r);
    r->id = id;
    sqlite3_bind_int64(s->current, 1, (sqlite3_int64)id);
    int rc = sqlite3_step(s->current);
    ts_status status;
    if (rc == SQLITE_ROW) {
        r->revision = (uint64_t)sqlite3_column_int64(s->current, 0);
        r->schema = (uint32_t)sqlite3_column_int64(s->current, 1);
        r->commit = (uint64_t)sqlite3_column_int64(s->current, 4);
        /* A removed record keeps its revision, so a reader can capture "removed at N". */
        status = sqlite3_column_int(s->current, 2) ? fill(s->current, 3, r, buffer, capacity) : TS_NOT_FOUND;
    } else status = rc == SQLITE_DONE ? TS_NOT_FOUND : code(rc);
    sqlite3_reset(s->current);
    return status;
}

ts_status ts_get_revision(ts_store *s, uint64_t id, uint64_t revision, ts_record *r, void *buffer, size_t capacity) {
    memset(r, 0, sizeof *r);
    r->id = id;
    r->revision = revision;
    sqlite3_bind_int64(s->at_revision, 1, (sqlite3_int64)id);
    sqlite3_bind_int64(s->at_revision, 2, (sqlite3_int64)revision);
    int rc = sqlite3_step(s->at_revision);
    ts_status status;
    if (rc == SQLITE_ROW) {
        r->schema = (uint32_t)sqlite3_column_int64(s->at_revision, 0);
        r->commit = (uint64_t)sqlite3_column_int64(s->at_revision, 3);
        status = sqlite3_column_int(s->at_revision, 2) ? TS_NOT_FOUND : fill(s->at_revision, 1, r, buffer, capacity);
    } else status = rc == SQLITE_DONE ? TS_NOT_FOUND : code(rc);
    sqlite3_reset(s->at_revision);
    return status;
}

ts_status ts_effect_get(ts_store *s, const uint8_t intent[16], ts_effect_state *state, uint32_t *kind) {
    sqlite3_bind_blob(s->effect, 1, intent, 16, SQLITE_STATIC);
    int rc = sqlite3_step(s->effect);
    ts_status status = rc == SQLITE_ROW ? TS_OK : rc == SQLITE_DONE ? TS_NOT_FOUND : code(rc);
    if (rc == SQLITE_ROW) {
        if (state) *state = (ts_effect_state)sqlite3_column_int(s->effect, 0);
        if (kind) *kind = (uint32_t)sqlite3_column_int64(s->effect, 1);
    }
    sqlite3_reset(s->effect);
    return status;
}

/* ---- commit ---- */
static int allowed(ts_effect_state from, ts_effect_state to) {
    switch (from) {
    case TS_PREPARED: return to == TS_AUTHORISED || to == TS_CANCELLED;
    case TS_AUTHORISED: return to == TS_ISSUED || to == TS_CANCELLED;
    case TS_ISSUED: return to == TS_OBSERVED || to == TS_FAILED || to == TS_UNKNOWN;
    case TS_UNKNOWN: return to == TS_OBSERVED || to == TS_FAILED;   /* reconciliation only */
    default: return 0;                                              /* terminal */
    }
}

static ts_status validate(const ts_read *reads, size_t nr, const ts_write *writes, size_t nw,
                          const ts_effect *effects, size_t ne) {
    if (nr > BATCH_LIMIT || nw > BATCH_LIMIT || ne > BATCH_LIMIT) return TS_CAPACITY;
    if ((nr && !reads) || (nw && !writes) || (ne && !effects)) return TS_INVALID;
    for (size_t i = 0; i < nr; i++) {
        if (!reads[i].id) return TS_INVALID;
        for (size_t j = 0; j < i; j++) if (reads[j].id == reads[i].id) return TS_INVALID;
    }
    for (size_t i = 0; i < nw; i++) {
        const ts_write *w = &writes[i];
        if (w->remove ? !w->id : (w->size > TS_DATA_LIMIT || (w->size && !w->data))) return TS_INVALID;
        if (!w->id) continue;
        size_t j = 0;
        while (j < nr && reads[j].id != w->id) j++;
        if (j == nr) return TS_INVALID;   /* a blind write would lose concurrent updates */
        for (j = 0; j < i; j++) if (writes[j].id == w->id) return TS_INVALID;
    }
    for (size_t i = 0; i < ne; i++) {
        const ts_effect *e = &effects[i];
        if (e->size > TS_DATA_LIMIT || (e->size && !e->detail)) return TS_INVALID;
        if (e->from ? !allowed(e->from, e->to) : e->to != TS_PREPARED) return TS_INVALID;
        for (size_t j = 0; j < i; j++) if (!memcmp(effects[j].intent, e->intent, 16)) return TS_INVALID;
    }
    return TS_OK;
}

static int bind_detail(sqlite3_stmt *st, int column, const void *data, size_t size) {
    return sqlite3_bind_blob64(st, column, size ? data : "", size, SQLITE_STATIC);
}

ts_status ts_commit(ts_store *s, const ts_read *reads, size_t nr, ts_write *writes, size_t nw,
                    const ts_effect *effects, size_t ne, ts_receipt *receipt) {
    memset(receipt, 0, sizeof *receipt);
    ts_status status = validate(reads, nr, writes, nw, effects, ne);
    if (status) return status;
    int rc = exec(s, "BEGIN IMMEDIATE");
    if (rc) return code(rc);
    size_t changed = 0;
    sqlite3_int64 seq = 0;
    status = TS_IO;

    /* 1. Every captured read is still current, before anything is written. */
    for (size_t i = 0; i < nr; i++) {
        sqlite3_bind_int64(s->revision, 1, (sqlite3_int64)reads[i].id);
        rc = sqlite3_step(s->revision);
        uint64_t current = rc == SQLITE_ROW ? (uint64_t)sqlite3_column_int64(s->revision, 0) : 0;
        int live = rc == SQLITE_ROW && sqlite3_column_int(s->revision, 1);
        sqlite3_reset(s->revision);
        if (rc != SQLITE_ROW && rc != SQLITE_DONE) { status = code(rc); goto fail; }
        if (current != reads[i].revision) { status = TS_CONFLICT; goto fail; }
        for (size_t j = 0; j < nw; j++)   /* removed records are final; ids are never revived */
            if (writes[j].id == reads[i].id && !live) { status = TS_INVALID; goto fail; }
    }
    /* 2. Every effect is in the state the caller saw. */
    for (size_t i = 0; i < ne; i++) {
        const ts_effect *e = &effects[i];
        sqlite3_bind_blob(s->effect, 1, e->intent, 16, SQLITE_STATIC);
        rc = sqlite3_step(s->effect);
        if (rc == SQLITE_ROW) {
            int state = sqlite3_column_int(s->effect, 0);
            uint32_t kind = (uint32_t)sqlite3_column_int64(s->effect, 1);
            const void *request = sqlite3_column_blob(s->effect, 2);
            size_t size = (size_t)sqlite3_column_bytes(s->effect, 2);
            /* An identical prepare is a retry, not a second intent. */
            int retry = !e->from && kind == e->kind && size == e->size && (!size || !memcmp(request, e->detail, size));
            sqlite3_reset(s->effect);
            if (e->from ? (int)e->from != state : !retry) { status = TS_CONFLICT; goto fail; }
        } else {
            sqlite3_reset(s->effect);
            if (rc != SQLITE_DONE) { status = code(rc); goto fail; }
            if (e->from) { status = TS_NOT_FOUND; goto fail; }
        }
    }
    /* 3. Apply under one commit sequence; unchanged values do not make revisions. */
    if ((rc = step_done(s->insert_commit))) { status = code(rc); goto fail; }
    seq = sqlite3_last_insert_rowid(s->db);
    for (size_t i = 0; i < nw; i++) {
        ts_write *w = &writes[i];
        uint64_t revision = 1;
        if (w->id) {
            const ts_read *r = reads;
            while (r->id != w->id) r++;
            sqlite3_bind_int64(s->current, 1, (sqlite3_int64)w->id);
            rc = sqlite3_step(s->current);
            int same = rc == SQLITE_ROW && !w->remove &&
                (uint32_t)sqlite3_column_int64(s->current, 1) == w->schema &&
                (size_t)sqlite3_column_bytes(s->current, 3) == w->size &&
                (!w->size || !memcmp(sqlite3_column_blob(s->current, 3), w->data, w->size));
            sqlite3_reset(s->current);
            if (rc != SQLITE_ROW) { status = rc == SQLITE_DONE ? TS_INVALID : code(rc); goto fail; }
            if (same) continue;
            revision = r->revision + 1;
            sqlite3_bind_int64(s->update, 1, (sqlite3_int64)revision);
            sqlite3_bind_int64(s->update, 2, w->schema);
            sqlite3_bind_int(s->update, 3, !w->remove);
            sqlite3_bind_int64(s->update, 4, (sqlite3_int64)w->id);
            if ((rc = step_done(s->update))) { status = code(rc); goto fail; }
        } else {
            sqlite3_bind_int64(s->create, 1, w->schema);
            if ((rc = step_done(s->create))) { status = code(rc); goto fail; }
            w->id = (uint64_t)sqlite3_last_insert_rowid(s->db);
        }
        sqlite3_bind_int64(s->insert_revision, 1, (sqlite3_int64)w->id);
        sqlite3_bind_int64(s->insert_revision, 2, (sqlite3_int64)revision);
        sqlite3_bind_int64(s->insert_revision, 3, w->schema);
        bind_detail(s->insert_revision, 4, w->remove ? 0 : w->data, w->remove ? 0 : w->size);
        sqlite3_bind_int(s->insert_revision, 5, w->remove != 0);
        sqlite3_bind_int64(s->insert_revision, 6, seq);
        if ((rc = step_done(s->insert_revision))) { status = code(rc); goto fail; }
        changed++;
    }
    for (size_t i = 0; i < ne; i++) {
        const ts_effect *e = &effects[i];
        if (!e->from) {
            sqlite3_bind_blob(s->effect_insert, 1, e->intent, 16, SQLITE_STATIC);
            sqlite3_bind_int64(s->effect_insert, 2, e->kind);
            sqlite3_bind_int(s->effect_insert, 3, TS_PREPARED);
            bind_detail(s->effect_insert, 4, e->detail, e->size);
            sqlite3_bind_int64(s->effect_insert, 5, seq);
            rc = step_done(s->effect_insert);
            if (rc == SQLITE_CONSTRAINT) continue;   /* the identical retry found above */
        } else {
            sqlite3_bind_int(s->effect_update, 1, e->to);
            if (e->to == TS_OBSERVED || e->to == TS_FAILED) bind_detail(s->effect_update, 2, e->detail, e->size);
            else sqlite3_bind_null(s->effect_update, 2);
            sqlite3_bind_int64(s->effect_update, 3, seq);
            sqlite3_bind_blob(s->effect_update, 4, e->intent, 16, SQLITE_STATIC);
            rc = step_done(s->effect_update);
        }
        if (rc) { status = code(rc); goto fail; }
        sqlite3_bind_blob(s->effect_log, 1, e->intent, 16, SQLITE_STATIC);
        sqlite3_bind_int64(s->effect_log, 2, seq);
        sqlite3_bind_int(s->effect_log, 3, e->from);
        sqlite3_bind_int(s->effect_log, 4, e->to);
        if ((rc = step_done(s->effect_log))) { status = code(rc); goto fail; }
        changed++;
    }
    if (!changed) {   /* nothing to make durable: no commit sequence is consumed */
        exec(s, "ROLLBACK");
        return TS_OK;
    }
    if ((rc = exec(s, "COMMIT"))) { status = code(rc); goto fail; }
    receipt->commit = (uint64_t)seq;
    receipt->changed = changed;
    return TS_OK;
fail:
    exec(s, "ROLLBACK");
    /* Ids assigned inside the rolled-back transaction never existed. */
    if (seq) for (size_t i = 0; i < nw; i++) {
        size_t j = 0;
        while (j < nr && reads[j].id != writes[i].id) j++;
        if (j == nr) writes[i].id = 0;
    }
    return status;
}

/* ---- objects ---- */
static void hex(const uint8_t hash[32], char out[65]) {
    for (int i = 0; i < 32; i++) snprintf(out + 2 * i, 3, "%02x", hash[i]);
}

static ts_status read_object(int dir, const char *name, const uint8_t hash[32], void *buffer, size_t capacity, size_t *size) {
    int fd = openat(dir, name, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? TS_NOT_FOUND : TS_IO;
    struct stat st;
    ts_status status = TS_IO;
    if (fstat(fd, &st)) goto done;
    *size = (size_t)st.st_size;
    if (*size > capacity) { status = TS_CAPACITY; goto done; }
    size_t got = 0;
    while (got < *size) {
        ssize_t n = read(fd, (char *)buffer + got, *size - got);
        if (n <= 0) { if (n < 0 && errno == EINTR) continue; goto done; }
        got += (size_t)n;
    }
    uint8_t actual[32];
    ts_sha256(buffer, *size, actual);
    status = memcmp(actual, hash, 32) ? TS_IO : TS_OK;
done:
    close(fd);
    return status;
}

ts_status ts_object_get(ts_store *s, const uint8_t hash[32], void *buffer, size_t capacity, size_t *size) {
    char h[65], name[80];
    hex(hash, h);
    snprintf(name, sizeof name, "%.2s/%s", h, h + 2);
    *size = 0;
    return read_object(s->objects, name, hash, buffer, capacity, size);
}

ts_status ts_object_put(ts_store *s, const void *data, size_t size, uint8_t hash[32]) {
    if (size && !data) return TS_INVALID;
    ts_sha256(data, size, hash);
    char h[65], name[80], tmp[64];
    hex(hash, h);
    snprintf(name, sizeof name, "%.2s/%s", h, h + 2);
    struct stat st;
    if (!fstatat(s->objects, name, &st, 0) && (size_t)st.st_size == size) return TS_OK;  /* immutable: already there */
    char sub[3] = {h[0], h[1], 0};
    if (mkdirat(s->objects, sub, 0700) && errno != EEXIST) return TS_IO;
    snprintf(tmp, sizeof tmp, "tmp-%ld-%llu", (long)getpid(), (unsigned long long)s->tmp_counter++);
    int fd = openat(s->objects, tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return TS_IO;
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, (const char *)data + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { int full = errno == ENOSPC || errno == EDQUOT; close(fd); unlinkat(s->objects, tmp, 0); return full ? TS_FULL : TS_IO; }
        done += (size_t)n;
    }
    int ok = !fsync(fd);
    ok = !close(fd) && ok;
    /* Visible only complete and synced; then the directory entries themselves are synced. */
    if (!ok || renameat(s->objects, tmp, s->objects, name)) { unlinkat(s->objects, tmp, 0); return TS_IO; }
    int subfd = openat(s->objects, sub, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    ok = subfd >= 0 && !fsync(subfd) && !fsync(s->objects);
    if (subfd >= 0) close(subfd);
    return ok ? TS_OK : TS_IO;
}
