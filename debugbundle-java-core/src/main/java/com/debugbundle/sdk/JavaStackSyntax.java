package com.debugbundle.sdk;

import java.util.regex.Pattern;

/** JVM stack syntax, including classloader/module@version prefixes. No log-file prefixes. */
final class JavaStackSyntax {
    static final Pattern ROOT = Pattern.compile(
            "^(?:Exception in thread \\\"[^\\\"]{1,128}\\\"\\s+)?((?:[\\w.$/]{0,512}(?:Exception|Error|Throwable))|(?:[\\w$]+\\.)+[\\w$]+)(?::[^\\r\\n]*)?$");
    static final Pattern CONTINUATION = Pattern.compile(
            "^\\s*(?:at\\s+[^\\s()]{1,1024}\\([^\\r\\n()]{1,4096}\\)|\\.\\.\\.\\s+\\d+\\s+more|"
            + "(?:Caused by|Suppressed):\\s+[\\w.$/]{1,512}(?::[^\\r\\n]*)?|"
            + "\\[CIRCULAR REFERENCE:\\s+[^\\r\\n]{1,4096}\\])\\s*$");

    static boolean redirected(String logger) {
        return logger != null && logger.length() <= 256 && (logger.equals("stderr")
                || logger.equals("org.jboss.stdio") || logger.startsWith("org.jboss.stdio."));
    }

    private JavaStackSyntax() { }
}
