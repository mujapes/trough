package com.trough.hot;

import java.util.Locale;
import java.util.Map;

/** Connection failures are separate from task failures, quota and explicit sign-in. */
final class ConnectionRecovery {
    static long retryDelay(int failures) { return Math.min(30000L,1000L<<Math.max(0,Math.min(failures,5))); }
    private static String lower(String text) { return text==null?"":text.toLowerCase(Locale.ROOT); }
    static boolean rateLimited(String text) {
        String s=lower(text);
        return s.contains("rate limit") || s.matches("(?s).*\\b429\\b.*");
    }
    static boolean requiresSignIn(String text) {
        String s=lower(text);
        return s.contains("unauthorized") || s.matches("(?s).*\\b401\\b.*")
            || s.contains("invalid_grant") || s.contains("invalid grant") || s.contains("invalid refresh token")
            || s.contains("refresh token has expired") || s.contains("refresh token has been revoked")
            || s.contains("refresh token was revoked") || s.contains("refresh_token_reused")
            || s.contains("sign-in required") || s.contains("sign in required") || s.contains("please sign in")
            || s.contains("authentication required") || s.contains("not authenticated");
    }
    static boolean retryNotice(String text) {
        String s=lower(text).trim();
        return s.equals("reconnecting") || s.startsWith("reconnecting...") || s.startsWith("reconnecting ");
    }
    static boolean transientFailure(String text) {
        String s=lower(text);
        if(requiresSignIn(s) || rateLimited(s)) return false;
        return retryNotice(s) || s.contains("connection") || s.contains("network") || s.contains("disconnected")
            || s.contains("stream closed") || s.contains("stream reset") || s.contains("stream error")
            || s.contains("stream disconnected") || s.contains("responsestreamdisconnected")
            || s.contains("responsestreamconnectionfailed") || s.contains("httpconnectionfailed")
            || s.contains("timed out") || s.contains("timeout") || s.contains("econnreset")
            || s.contains("econnrefused") || s.contains("enetunreach") || s.contains("ehostunreach")
            || s.contains("eai_again") || s.contains("failed to lookup address information")
            || s.contains("temporary failure in name resolution") || s.contains("dns") || s.contains("socket")
            || s.contains("broken pipe") || s.contains("service unavailable");
    }
    static String errorText(Map<String,Object> error) {
        String message=Json.str(error,"message");
        if(message==null) message="";
        Object info=error==null?null:error.get("codexErrorInfo");
        String detail=info==null?"":Json.write(info);
        if(requiresSignIn(detail)) return "Sign-in required: "+message;
        if(detail.matches("(?s).*\\b429\\b.*")) return "Rate limit (429): "+message;
        if(info!=null && !requiresSignIn(message) && transientFailure(detail))
            return "Connection interrupted: "+message;
        return message;
    }
}
