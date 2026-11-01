package idp.ops

import idp.ops.cli.CLI_COMMAND
import idp.ops.cli.runCli
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class OpsApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == CLI_COMMAND) exitProcess(runCli(args.drop(1)))
    runApplication<OpsApplication>(*args)
}
