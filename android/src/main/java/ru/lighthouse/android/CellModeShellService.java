package ru.lighthouse.android;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Shizuku UserService: only the shell UID can run the guarded phone command. */
public final class CellModeShellService extends Binder {
    static final int OPEN = 1, APPLY = 2, RESTORE = 3;
    private long original = -1;
    private int slot = -1;
    private IBinder owner;
    private IBinder.DeathRecipient ownerDeath;

    public CellModeShellService() {}

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != OPEN && code != APPLY && code != RESTORE && code != 16777115)
            return super.onTransact(code, data, reply, flags);
        String result;
        try {
            synchronized (this) {
                if (code == OPEN) result = open(data.readInt(), data.readStrongBinder());
                else if (code == APPLY) result = apply(data.readInt());
                else result = restore();
            }
            if (reply != null) { reply.writeNoException(); reply.writeInt(1); reply.writeString(result); }
        } catch (Exception failure) {
            if (reply != null) {
                reply.writeNoException(); reply.writeInt(0);
                reply.writeString(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
        }
        if (code == 16777115) new Thread(() -> System.exit(0), "cell-mode-exit").start();
        return true;
    }

    private String open(int requestedSlot, IBinder requestedOwner) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IOException("ADB shell access is required");
        if (requestedSlot < 0 || requestedSlot > 3 || requestedOwner == null) throw new IOException("Invalid SIM slot");
        if (original >= 0) {
            if (slot == requestedSlot && owner == requestedOwner) return "ready";
            throw new IOException("Another Cell survey is active");
        }
        long current = readMode(requestedSlot);
        if (current <= 0) throw new IOException("The original network mode could not be read safely");
        IBinder.DeathRecipient death = () -> new Thread(() -> {
            synchronized (CellModeShellService.this) {
                try { restore(); } catch (Exception ignored) { /* Retained for a later retry. */ }
            }
        }, "cell-mode-owner-death").start();
        requestedOwner.linkToDeath(death, 0);
        owner = requestedOwner; ownerDeath = death; slot = requestedSlot; original = current;
        return "ready";
    }

    private String apply(int mode) throws Exception {
        if (original < 0) throw new IOException("Cell survey was not started");
        long requested = switch (mode) {
            case 0 -> bits(13, 19);                  // LTE, LTE-CA
            case 1 -> bits(20, 13, 19);              // NR plus LTE anchor for NSA
            case 2 -> bits(3, 8, 9, 10, 15, 17);    // UMTS/HSPA/TD-SCDMA
            case 3 -> bits(16, 1, 2);                // GSM, GPRS, EDGE
            default -> throw new IOException("Unknown network mode");
        };
        try { setMode(slot, requested); }
        catch (Exception failure) {
            try { restore(); } catch (Exception ignored) { failure.addSuppressed(ignored); }
            throw failure;
        }
        return new String[]{"LTE", "5G NR + LTE", "3G", "GSM"}[mode];
    }

    private String restore() throws Exception {
        if (original < 0) return "restored";
        setMode(slot, original);
        if (owner != null && ownerDeath != null) owner.unlinkToDeath(ownerDeath, 0);
        original = -1; slot = -1; owner = null; ownerDeath = null;
        return "restored";
    }

    private static void setMode(int simSlot, long mask) throws Exception {
        String result = command("set-allowed-network-types-for-users", "-s", Integer.toString(simSlot), Long.toBinaryString(mask));
        if (!result.contains("set-allowed-network-types-for-users completed"))
            throw new IOException("Android rejected the network mode: " + result);
        if (readMode(simSlot) != mask) throw new IOException("Network mode readback did not match the request");
    }

    private static long readMode(int simSlot) throws Exception {
        String output = command("get-allowed-network-types-for-users", "-s", Integer.toString(simSlot));
        String[] lines = output.split("\\R");
        String names = lines[lines.length - 1].trim();
        if (names.isEmpty() || names.equals("UNKNOWN")) throw new IOException("Unknown current network mode");
        long mask = 0;
        for (String name : names.split("\\|")) {
            int type = switch (name.trim()) {
                case "GPRS" -> 1; case "EDGE" -> 2; case "UMTS" -> 3;
                case "CDMA" -> 4; case "CDMA - EvDo rev. 0" -> 5;
                case "CDMA - EvDo rev. A" -> 6; case "CDMA - 1xRTT" -> 7;
                case "HSDPA" -> 8; case "HSUPA" -> 9; case "HSPA" -> 10;
                case "iDEN" -> 11; case "CDMA - EvDo rev. B" -> 12;
                case "LTE" -> 13; case "CDMA - eHRPD" -> 14; case "HSPA+" -> 15;
                case "GSM" -> 16; case "TD_SCDMA" -> 17; case "IWLAN" -> 18;
                case "LTE_CA" -> 19; case "NR" -> 20; case "NB_IOT_NTN" -> 21;
                default -> throw new IOException("Unrecognized network type: " + name);
            };
            mask |= bits(type);
        }
        return mask;
    }

    private static long bits(int... types) {
        long mask = 0;
        for (int type : types) mask |= 1L << (type - 1);
        return mask;
    }

    private static String command(String... args) throws Exception {
        String[] command = new String[args.length + 2]; command[0] = "/system/bin/cmd"; command[1] = "phone";
        System.arraycopy(args, 0, command, 2, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) throw new IOException("Telephony command timed out");
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null && output.length() < 2048) output.append(line).append('\n');
            }
            String result = output.toString().trim();
            if (process.exitValue() != 0 || result.isEmpty()) throw new IOException("Telephony command failed: " + result);
            return result;
        } finally { process.destroyForcibly(); }
    }
}
