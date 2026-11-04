package me.sfclog.agentbridge.plugin.dev;

import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.plugin.ConsoleService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Offline checks for ConsoleService filtering + self-reload deferral. */
public class ConsoleServiceDevTest {

    static final List<String> dispatched = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        ConsoleService svc = new ConsoleService(
                true,
                List.of(),
                List.of("stop", "restart", "reload", "op", "deop"),
                2000,
                (cmd, timeout) -> {
                    dispatched.add(cmd);
                    return List.of("ran: " + cmd);
                },
                "AgentBridge");

        check(svc, "list", 0, "ran: list");
        check(svc, "say hello", 0, "ran: say hello");
        check(svc, "/tps", 0, "ran: tps");
        check(svc, "stop", -1, "blacklisted");
        check(svc, "minecraft:op Steve", -1, "blacklisted");
        check(svc, "plugman reload MagicMenu", 0, "ran: plugman reload MagicMenu");
        check(svc, "plugman list AgentBridge", 0, "ran: plugman list AgentBridge");
        check(svc, "plugman list", 0, "ran: plugman list");
        check(svc, "plugman reload AgentBridge", 0, "Dispatched");
        check(svc, "plugman disable agentbridge", 0, "Dispatched");
        check(svc, "plugman reload all", 0, "Dispatched");

        Thread.sleep(2500);
        System.out.println("dispatched after delay: " + dispatched);
        if (!dispatched.contains("plugman reload AgentBridge")) {
            throw new AssertionError("deferred command never ran");
        }
        System.out.println("ALL OK");
    }

    static void check(ConsoleService svc, String cmd, int expectedExit, String expectContains) throws Exception {
        Meta m = svc.execute(cmd, 0);
        String out = (m.stdout == null ? "" : m.stdout) + (m.stderr == null ? "" : m.stderr);
        boolean ok = m.exitCode == expectedExit && out.contains(expectContains);
        System.out.println((ok ? "PASS " : "FAIL ") + "\"" + cmd + "\" exit=" + m.exitCode
                + " out=" + out.replace("\n", " | "));
        if (!ok) throw new AssertionError("expected exit=" + expectedExit + " containing " + expectContains);
    }
}
