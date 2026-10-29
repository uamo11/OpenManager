package rikka.shizuku;

import moe.shizuku.server.IRemoteProcess;

/**
 * Factory granting OpenManager access to Shizuku's remote process execution.
 * Located in package rikka.shizuku to access the package-private ShizukuRemoteProcess constructor
 * and protected requireService() accessor.
 */
public final class ShizukuProcessFactory {

    public Process newProcess(String[] cmd, String[] env, String dir) {
        try {
            IRemoteProcess rp = Shizuku.requireService().newProcess(cmd, env, dir);
            return new ShizukuRemoteProcess(rp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
