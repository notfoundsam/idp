package idp.sharedkernel.bus

interface Query<R> : Message<R>

class QueryBus(
    private val pipeline: BusPipeline,
) {
    fun <R> ask(query: Query<R>): R = pipeline.dispatch(query)
}
