package fi.kela.flows

import fi.kela.states.AccountState
import fi.kela.states.Balance
import fi.kela.states.Tag
import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import java.util.UUID

class GetAccountRequest()

class AccountResult(
                    val description: String,
                    val issuer: MemberX500Name,
                    val balances: List<Balance>,
                    val tags: List<Tag>,)

/**
 * NOT TO BE USED
 */
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

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(requestBody: ClientRequestBody):String{
        val flowArgs: GetAccountRequest = requestBody.getRequestBodyAs(jsonMarshallingService, GetAccountRequest::class.java)
        val states = ledgerService.findUnconsumedStatesByType(AccountState::class.java)
        val myInfo = memberLookup.myInfo();
        val balances = flowEngine.subFlow(GetTokensFlowInternal(myInfo.name.toString()))
        val results = states.map {
            AccountResult(
                it.state.contractState.description,
                it.state.contractState.issuer,
                it.state.contractState.balances,
                it.state.contractState.tags) }
        results.forEach{ it:AccountResult -> log.warn("account ${it}")}
        val result = results.singleOrNull {it -> it.issuer == myInfo.name}

            ?: throw CordaRuntimeException("Did not find an unique unconsumed Account")

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