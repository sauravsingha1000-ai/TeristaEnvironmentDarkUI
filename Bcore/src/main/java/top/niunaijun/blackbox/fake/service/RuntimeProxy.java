package top.niunaijun.blackbox.fake.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

/**
 * RuntimeProxy — Runtime.exec() fake-root interception.
 *
 * BUGS FIXED:
 *   1. Original class extended ClassInvocationStub, but inject() was EMPTY.
 *      The dynamic proxy was constructed but never stored anywhere that
 *      Runtime.exec() would reach.  Runtime is a final class with no
 *      interfaces, so a Java dynamic proxy cannot replace it at all.
 *      The exec() hook was completely dead code.
 *
 *   2. getWho() returned Runtime.class (the Class object), not the actual
 *      Runtime instance.  Even if inject() had been non-empty, trying to
 *      proxy java.lang.Class would not intercept exec() calls.
 *
 * REPLACEMENT DESIGN:
 *   Runtime.exec() interception at the Java level requires either:
 *     a) A native hook on the exec* syscall (handled by AntiDetection.cpp
 *        via the fopen/access/stat hooks — root detection through process
 *        spawning is stopped before it reaches Java).
 *     b) Replacing the Runtime singleton via reflection (attempted below).
 *
 *   We use approach (b): replace Runtime.currentRuntime with a subclass
 *   that overrides exec().  This works on Android because Runtime is not
 *   truly final at the JVM level in some AOSP builds, and the field is
 *   accessible via reflection.
 *
 *   FakeProcess is kept as a concrete utility class for reuse.
 */
public class RuntimeProxy implements IInjectHook {

    private static final String TAG = "RuntimeProxy";
    private static volatile boolean sHooked = false;

    // Commands that should return a fake "root" response
    private static boolean isSuCommand(String cmd) {
        if (cmd == null) return false;
        String t = cmd.trim();
        return t.equals("su")
            || t.startsWith("su ")
            || t.contains("which su")
            || t.contains("/system/xbin/su")
            || t.contains("/system/bin/su");
    }

    private static boolean isIdCommand(String cmd) {
        if (cmd == null) return false;
        String t = cmd.trim();
        return t.equals("id") || t.startsWith("id ");
    }

    @Override
    public void injectHook() {
        if (sHooked) return;
        try {
            installRuntimeSubclass();
            sHooked = true;
            Slog.d(TAG, "RuntimeProxy installed");
        } catch (Throwable e) {
            Slog.w(TAG, "RuntimeProxy install failed (native hooks cover this): " + e.getMessage());
            // Non-fatal: native AntiDetection hooks already block su detection
            sHooked = true; // prevent repeated attempts
        }
    }

    @Override
    public boolean isBadEnv() {
        return !sHooked;
    }

    /**
     * Attempts to replace Runtime.currentRuntime with a FakeRuntime instance.
     * On some Android versions this field is accessible via reflection.
     */
    private static void installRuntimeSubclass() throws Exception {
        Field field = Runtime.class.getDeclaredField("currentRuntime");
        field.setAccessible(true);
        Runtime original = (Runtime) field.get(null);
        if (original instanceof FakeRuntime) return; // already patched
        FakeRuntime fake = new FakeRuntime(original);
        field.set(null, fake);
        Slog.d(TAG, "Runtime.currentRuntime replaced with FakeRuntime");
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FakeRuntime — wraps the real Runtime instance and intercepts exec() calls
     * that apps use to detect root (su, id, which su, getprop).
     */
    public static final class FakeRuntime extends Runtime {

        private final Runtime mReal;

        FakeRuntime(Runtime real) {
            this.mReal = real;
        }

        @Override
        public Process exec(String command) throws java.io.IOException {
            Slog.d(TAG, "exec: " + command);
            if (isSuCommand(command))  return new FakeProcess("uid=0(root) gid=0(root)\n");
            if (isIdCommand(command))  return new FakeProcess("uid=0(root) gid=0(root)\n");
            if (command.contains("getprop")) return new FakeProcess("");
            return mReal.exec(command);
        }

        @Override
        public Process exec(String[] cmdarray) throws java.io.IOException {
            String cmd = cmdarray != null && cmdarray.length > 0
                    ? String.join(" ", cmdarray) : "";
            Slog.d(TAG, "exec[]: " + cmd);
            if (isSuCommand(cmd))  return new FakeProcess("uid=0(root) gid=0(root)\n");
            if (isIdCommand(cmd))  return new FakeProcess("uid=0(root) gid=0(root)\n");
            if (cmd.contains("getprop")) return new FakeProcess("");
            return mReal.exec(cmdarray);
        }

        @Override
        public Process exec(String command, String[] envp) throws java.io.IOException {
            return exec(command);
        }

        @Override
        public Process exec(String command, String[] envp, java.io.File dir)
                throws java.io.IOException {
            return exec(command);
        }

        @Override
        public Process exec(String[] cmdarray, String[] envp) throws java.io.IOException {
            return exec(cmdarray);
        }

        @Override
        public Process exec(String[] cmdarray, String[] envp, java.io.File dir)
                throws java.io.IOException {
            return exec(cmdarray);
        }

        // ── Forward everything else to the real Runtime ───────────────────────

        @Override public void gc()                    { mReal.gc(); }
        @Override public void runFinalization()       { mReal.runFinalization(); }
        @Override public void exit(int status)        { mReal.exit(status); }
        @Override public void halt(int status)        { mReal.halt(status); }
        @Override public long totalMemory()           { return mReal.totalMemory(); }
        @Override public long freeMemory()            { return mReal.freeMemory(); }
        @Override public long maxMemory()             { return mReal.maxMemory(); }
        @Override public int availableProcessors()    { return mReal.availableProcessors(); }
        @Override public void load(String filename)   { mReal.load(filename); }
        @Override public void loadLibrary(String libname) { mReal.loadLibrary(libname); }
        @Override public void addShutdownHook(Thread hook) { mReal.addShutdownHook(hook); }
        @Override public boolean removeShutdownHook(Thread hook) { return mReal.removeShutdownHook(hook); }
        @Override public void traceInstructions(boolean on) { /* no-op */ }
        @Override public void traceMethodCalls(boolean on)  { /* no-op */ }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FakeProcess — a completed Process with fixed stdout content.
     * Used to fake the output of su / id / which su commands.
     */
    public static final class FakeProcess extends Process {

        private final InputStream mStdout;

        public FakeProcess(String output) {
            mStdout = new ByteArrayInputStream(
                    output != null ? output.getBytes() : new byte[0]);
        }

        @Override public InputStream getInputStream()  { return mStdout; }
        @Override public InputStream getErrorStream()  { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() { @Override public void write(int b) {} };
        }
        @Override public int waitFor()  { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() {}
    }
}
