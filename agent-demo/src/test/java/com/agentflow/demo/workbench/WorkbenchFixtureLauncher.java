package com.agentflow.demo.workbench;

import com.agentflow.demo.delivery.DeliveryDemoLauncher;

/** Test-only entrypoint; reuses the existing isolated, deterministic delivery backend. */
public final class WorkbenchFixtureLauncher {
    private WorkbenchFixtureLauncher() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--offline".equals(args[0])) {
            throw new IllegalArgumentException("Use --offline <loopback-port>");
        }
        var fixture = DeliveryDemoLauncher.start("happy-path", Integer.parseInt(args[1]));
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
        System.out.println("WORKBENCH_FIXTURE_READY " + args[1]);
    }
}
