package com.webhook.platform.cli.command;

import com.webhook.platform.cli.RailhookCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class RailhookCliTest {

    // listen has no command test of its own, so this is what fails if it drops out of the list.
    @Test
    void everySubcommandIsRegistered() {
        CommandLine cmd = new CommandLine(new RailhookCli());

        assertThat(cmd.getSubcommands().keySet()).contains(
                "login", "listen", "status", "replay", "tunnels", "config", "events", "admin");
    }
}
