package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [DefaultLocalSsmTooling]: which runner outcomes count as a missing tool. How a real
 * process maps to those outcomes is covered by [LocalCliRunnerTest].
 */
class LocalSsmToolingTest {
    private fun toolingWhere(results: Map<String, LocalCliResult>) =
        DefaultLocalSsmTooling(
            runner = { command, _ -> results.getValue(command.first()) },
            timeout = Duration.ofSeconds(1),
        )

    @Test
    fun `reports nothing when both tools exit zero`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(0, "aws-cli/2.17.0"),
                    "session-manager-plugin" to LocalCliResult.Completed(0, "1.2.650.0"),
                ),
            )

        assertThat(tooling.missingTools()).isEmpty()
    }

    @Test
    fun `a binary that is not on the PATH is missing`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(0, "aws-cli/2.17.0"),
                    "session-manager-plugin" to LocalCliResult.BinaryNotFound,
                ),
            )

        assertThat(tooling.missingTools()).containsExactly(SsmTool.SessionManagerPlugin)
    }

    @Test
    fun `a tool that exits non-zero or hangs is missing`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(1, ""),
                    "session-manager-plugin" to LocalCliResult.TimedOut,
                ),
            )

        assertThat(tooling.missingTools()).containsExactly(SsmTool.AwsCli, SsmTool.SessionManagerPlugin)
    }
}
