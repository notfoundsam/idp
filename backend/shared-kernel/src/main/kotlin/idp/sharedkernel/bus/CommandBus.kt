package idp.sharedkernel.bus

interface Command<R> : Message<R>

class CommandBus(
    private val pipeline: BusPipeline,
) {
    fun <R> dispatch(command: Command<R>): R = pipeline.dispatch(command)
}
