package me.sfclog.agentbridge.client;

import me.sfclog.agentbridge.common.Entry;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.common.Meta;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Command-line interface for AgentBridge.
 *
 * <pre>
 *   java -jar AgentBridge.jar [--config client.json] [--agent id] &lt;command&gt; ...
 *   java -jar AgentBridge.jar mcp
 * </pre>
 */
public class BridgeCli {

    public static void main(String[] argv) {
        // Keep stdout clean for data (cat/file output); logs go to stderr.
        Log.setSink((level, msg) -> System.err.println("[AgentBridge:" + level + "] " + msg));

        Args args = new Args(argv);

        // If the first positional token is "mcp", run the MCP stdio server
        if (!args.positional.isEmpty() && "mcp".equalsIgnoreCase(args.positional.get(0))) {
            BridgeMcpServer.main(argv);
            return;
        }

        int exit = new BridgeCli().run(args);
        System.exit(exit);
    }

    private int run(Args args) {
        if (args.positional.isEmpty()) {
            usage();
            return 1;
        }

        String cmd = args.positional.get(0);
        if ("help".equalsIgnoreCase(cmd) || "--help".equalsIgnoreCase(cmd)) {
            usage();
            return 0;
        }

        ClientConfig cfg;
        try {
            Path cfgPath = Paths.get(args.opt("config", "client.json"));
            cfg = ClientConfig.loadOrCreate(cfgPath);
        } catch (Exception e) {
            System.err.println("Failed to load config: " + e.getMessage());
            return 1;
        }

        // CLI overrides
        if (args.opt("host") != null) cfg.host = args.opt("host");
        if (args.opt("port") != null) cfg.port = args.intOpt("port", cfg.port);
        if (args.opt("token") != null) cfg.token = args.opt("token");
        if (args.opt("agent") != null) cfg.defaultAgent = args.opt("agent");

        String agent = args.opt("agent", cfg.defaultAgent);

        RemoteBridge bridge = new RemoteBridge(cfg);
        try {
            bridge.connect();
            return dispatch(bridge, args, cfg, agent);
        } catch (BridgeException e) {
            System.err.println("Error [" + e.code + "]: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (Log.isDebug()) e.printStackTrace(System.err);
            return 1;
        } finally {
            bridge.close();
        }
    }

    private int dispatch(RemoteBridge bridge, Args args, ClientConfig cfg, String agent) throws Exception {
        String cmd = args.positional.get(0).toLowerCase();
        String json = null;

        switch (cmd) {
            case "agents": {
                Meta m = bridge.agents();
                printAgents(m);
                return 0;
            }
            case "ls": case "list": {
                String path = args.positional.size() > 1 ? args.positional.get(1) : "";
                Meta m = bridge.list(agent, path);
                printList(path, m);
                return 0;
            }
            case "stat": {
                String path = require(args, 1, "stat <path>");
                Meta m = bridge.stat(agent, path);
                printStat(path, m);
                return m.exists ? 0 : 3;
            }
            case "exists": {
                String path = require(args, 1, "exists <path>");
                Meta m = bridge.exists(agent, path);
                System.out.println(m.exists ? "yes" : "no");
                return m.exists ? 0 : 3;
            }
            case "cat": case "read": {
                String path = require(args, 1, "cat <path>");
                long offset = args.longOpt("offset", 0);
                long length = args.longOpt("length", 0);
                Path tmp = Files.createTempFile("bridge-cat-", ".bin");
                try {
                    bridge.readToFile(agent, path, offset, length, tmp);
                    try (OutputStream os = System.out) {
                        Files.copy(tmp, os);
                        os.flush();
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
                return 0;
            }
            case "get": case "download": {
                String remote = require(args, 1, "get <remote> <local>");
                String local = args.positional.size() > 2
                        ? args.positional.get(2)
                        : Paths.get(remote).getFileName().toString();
                Path localPath = cfg.resolveDownload(local);
                long offset = args.longOpt("offset", 0);
                long length = args.longOpt("length", 0);
                Meta m = bridge.readToFile(agent, remote, offset, length, localPath);
                System.out.println("Downloaded " + remote + " -> " + localPath +
                        " (" + Files.size(localPath) + " bytes)");
                return 0;
            }
            case "put": case "upload": {
                String local = require(args, 1, "put <local> <remote>");
                String remote = args.positional.size() > 2
                        ? args.positional.get(2)
                        : Paths.get(local).getFileName().toString();
                String mode = args.flag("append") ? "append" : "truncate";
                boolean mkdirs = args.flag("mkdirs") || args.flag("create-dirs");
                Meta m = bridge.writeFromFile(agent, remote, Paths.get(local), mode, mkdirs);
                System.out.println("Uploaded " + local + " -> " + remote +
                        " (" + (m.written > 0 ? m.written : Files.size(Paths.get(local))) + " bytes)");
                return 0;
            }
            case "write": {
                String path = require(args, 1, "write <path> [content] [--append]");
                // Content may be given as a positional arg; otherwise read stdin.
                byte[] data = args.positional.size() > 2
                        ? String.join(" ", args.positional.subList(2, args.positional.size()))
                              .getBytes(StandardCharsets.UTF_8)
                        : System.in.readAllBytes();
                String mode = args.flag("append") ? "append" : "truncate";
                boolean mkdirs = args.flag("mkdirs") || args.flag("create-dirs");
                Meta m = bridge.writeBytes(agent, path, data, mode, mkdirs);
                System.out.println("Wrote " + data.length + " bytes to " + path);
                return 0;
            }
            case "edit": {
                String path = require(args, 1, "edit <path> --old <text> --new <text> [--all]");
                String oldStr = args.opt("old");
                String newStr = args.opt("new");
                if (oldStr == null || newStr == null) {
                    System.err.println("Usage: edit <path> --old <text> --new <text> [--all]");
                    return 1;
                }
                int n = bridge.editFile(agent, path, oldStr, newStr, args.flag("all"));
                System.out.println("Edited " + path + ": " + n + " replacement(s)");
                return 0;
            }
            case "rm": case "delete": {
                String path = require(args, 1, "rm <path> [--recursive]");
                bridge.delete(agent, path, args.flag("recursive") || args.flag("r"));
                System.out.println("Deleted " + path);
                return 0;
            }
            case "mv": case "move": {
                String src = require(args, 1, "mv <src> <dst>");
                String dst = require(args, 2, "mv <src> <dst>");
                bridge.move(agent, src, dst);
                System.out.println("Moved " + src + " -> " + dst);
                return 0;
            }
            case "mkdir": {
                String path = require(args, 1, "mkdir <path>");
                boolean parents = args.flag("p") || args.flag("parents");
                bridge.mkdir(agent, path, parents);
                System.out.println("Created " + path);
                return 0;
            }
            case "exec": {
                if (args.positional.size() < 2) {
                    System.err.println("Usage: exec <command...>");
                    return 1;
                }
                String command = String.join(" ", args.positional.subList(1, args.positional.size()));
                long timeout = args.longOpt("timeout", 0);
                Meta m = bridge.exec(agent, command, args.opt("cwd"), timeout);
                if (m.stdout != null && !m.stdout.isEmpty()) System.out.print(m.stdout);
                if (m.stdout != null && !m.stdout.endsWith("\n") && !m.stdout.isEmpty()) System.out.println();
                if (m.stderr != null && !m.stderr.isEmpty()) {
                    System.err.print(m.stderr);
                    if (!m.stderr.endsWith("\n")) System.err.println();
                }
                return m.exitCode;
            }
            case "console": case "cmd": {
                if (args.positional.size() < 2) {
                    System.err.println("Usage: console <command...>");
                    return 1;
                }
                String command = String.join(" ", args.positional.subList(1, args.positional.size()));
                long timeout = args.longOpt("timeout", 0);
                Meta m = bridge.console(agent, command, timeout);
                if (m.stdout != null && !m.stdout.isEmpty()) System.out.println(m.stdout);
                if (m.stderr != null && !m.stderr.isEmpty()) System.err.println(m.stderr);
                return m.exitCode;
            }
            case "ping": {
                bridge.agents();
                System.out.println("ok");
                return 0;
            }
            default:
                System.err.println("Unknown command: " + cmd);
                usage();
                return 1;
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void printAgents(Meta m) {
        if (m.agents == null || m.agents.isEmpty()) {
            System.out.println("(no agents connected)");
            return;
        }
        System.out.printf("%-24s %s%n", "ID", "NAME");
        m.agents.forEach((id, name) -> System.out.printf("%-24s %s%n", id, name));
    }

    private void printList(String path, Meta m) {
        if (m.entries == null || m.entries.isEmpty()) {
            System.out.println("(empty)");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.printf("%-12s %12s  %-19s  %s%n", "TYPE", "SIZE", "MODIFIED", "NAME");
        for (Entry e : m.entries) {
            System.out.printf("%-12s %12d  %-19s  %s%s%n",
                    e.dir ? "DIR" : "FILE",
                    e.dir ? 0 : e.size,
                    fmt.format(new Date(e.modified)),
                    e.name,
                    e.dir ? "/" : "");
        }
    }

    private void printStat(String path, Meta m) {
        if (!m.exists) {
            System.out.println(path + ": not found");
            return;
        }
        System.out.println("Path:     " + path);
        System.out.println("Type:     " + (m.isDir ? "directory" : "file"));
        if (!m.isDir) System.out.println("Size:     " + m.size + " bytes");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.println("Modified: " + fmt.format(new Date(m.modified)));
    }

    private String require(Args args, int index, String usage) {
        if (args.positional.size() <= index) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        return args.positional.get(index);
    }

    private void usage() {
        System.out.println("""
                AgentBridge CLI

                Usage: java -jar AgentBridge.jar [options] <command> [args]

                Global options:
                  --config <file>     client.json path (default: client.json)
                  --host <host>       override server host
                  --port <port>       override server port
                  --token <token>     override client token
                  --agent <id>        target agent (default: from config)

                Commands:
                  agents                        List connected agents
                  ls [path]                     List a directory
                  cat <path> [--offset N]       Print a file (binary-safe)
                  get <remote> [local]          Download a file
                  put <local> [remote]          Upload a file (--append, --mkdirs)
                  write <path> [content]        Write a file (content or stdin)
                  edit <path> --old <a> --new <b> Replace exact text (--all)
                  stat <path>                   Show file info
                  exists <path>                 Check existence
                  rm <path> [--recursive]       Delete
                  mv <src> <dst>                Move / rename
                  mkdir <path> [--parents]      Create directory
                  exec <command...> [--cwd d]   Run a shell command on the host
                  console <command...>          Run a Minecraft console command
                  ping                          Test connectivity
                  mcp                           Run MCP stdio server
                """);
    }
}