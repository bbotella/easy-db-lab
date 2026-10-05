package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.time.Duration

/**
 * A local program the `ssm` SSH transport cannot work without.
 *
 * @property executable the name looked up on PATH
 * @property installHint how to install it, shown when it is missing
 */
enum class SsmTool(
    val executable: String,
    val installHint: String,
) {
    AwsCli(Constants.Ssm.AWS_CLI, Constants.Ssm.AWS_CLI_INSTALL_HINT),
    SessionManagerPlugin(Constants.Ssm.SESSION_MANAGER_PLUGIN, Constants.Ssm.PLUGIN_INSTALL_HINT),
}

/**
 * Reports which [SsmTool]s are missing from this machine.
 *
 * `up` asks before it creates any AWS resource. Without these tools every SSH connection under
 * the `ssm` transport fails, and that would otherwise surface only after instances were running,
 * as a readiness timeout.
 */
fun interface LocalSsmTooling {
    /** Never throws; returns the tools that could not be run, in [SsmTool] order. */
    fun missingTools(): List<SsmTool>
}

/**
 * Default [LocalSsmTooling]: a tool counts as present when `<tool> --version` exits 0. A missing
 * binary, a non-zero exit, and a hang all count as missing, since none of them can carry a session.
 */
class DefaultLocalSsmTooling(
    private val runner: LocalCliRunner = DefaultLocalCliRunner,
    private val timeout: Duration = Duration.ofSeconds(Constants.Ssm.TOOL_CHECK_TIMEOUT_SECONDS),
) : LocalSsmTooling {
    override fun missingTools(): List<SsmTool> =
        SsmTool.entries.filterNot { tool ->
            val result = runner.run(listOf(tool.executable, "--version"), timeout)
            result is LocalCliResult.Completed && result.exitCode == 0
        }
}
