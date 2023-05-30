package fi.kela.flows

import fi.kela.states.AccountState
import fi.kela.states.Balance
import fi.kela.states.Tag
import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import java.util.UUID

class GetAccountRequest(val id: UUID)

class AccountResult(val id: UUID,
                    val description: String,
                    val issuer: MemberX500Name,
                    val balances: List<Balance>,
                    val tags: List<Tag>,)

class GetAccountFlow : ClientStartableFlow {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call(requestBody: ClientRequestBody):String{
        val flowArgs: GetAccountRequest = requestBody.getRequestBodyAs(jsonMarshallingService, GetAccountRequest::class.java)
        val states = ledgerService.findUnconsumedStatesByType(AccountState::class.java)
        val balances = flowEngine.subFlow(GetTokensFlowInternal(flowArgs.id))
        val results = states.map {
            AccountResult(
                it.state.contractState.id,
                it.state.contractState.description,
                it.state.contractState.issuer,
                it.state.contractState.balances,
                it.state.contractState.tags) }
        results.forEach{ it:AccountResult -> log.warn("account ${it.id}")}
        val result = results.singleOrNull {it.id == flowArgs.id}
            ?: throw CordaRuntimeException("Did not find an unique unconsumed Account with id ${flowArgs.id}")

        return jsonMarshallingService.format(result)

    }
}

class GetAccountFlowInternal(private val accountId: String) : SubFlow<StateAndRef<AccountState>> {

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @Suspendable
    override fun call(): StateAndRef<AccountState>{
        val states: List<StateAndRef<AccountState>> = ledgerService.findUnconsumedStatesByType(AccountState::class.java)
        val result = states.singleOrNull {it:StateAndRef<AccountState> -> it.state.contractState.id.toString() == accountId}
            ?: throw CordaRuntimeException("Did not find an unique unconsumed ChatState with id $accountId")

        return result

    }
}