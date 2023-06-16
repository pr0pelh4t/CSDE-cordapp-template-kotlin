package fi.kela.flows

import fi.kela.states.Balance
import fi.kela.states.Tag
import fi.kela.states.Token
import fi.kela.utils.Constants
import fi.kela.utils.Utils.Companion.toSecureHash
import net.corda.v5.application.crypto.DigestService
import net.corda.v5.application.flows.ClientRequestBody
import net.corda.v5.application.flows.ClientStartableFlow
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.SubFlow
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

class GetTokensRequest(val accountId: UUID)

class TokensResult(val currency: String,
                   val symbol: String,
                   val ownerHash: SecureHash?,
                   val amount: BigDecimal
)

class GetTokensFlow : ClientStartableFlow {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var digestService: DigestService

    @Suspendable
    override fun call(requestBody: ClientRequestBody):String{
        val flowArgs: GetTokensRequest = requestBody.getRequestBodyAs(jsonMarshallingService, GetTokensRequest::class.java)
        val states = ledgerService.findUnconsumedStatesByType(Token::class.java)
        val issuer = memberLookup.lookup(MemberX500Name.parse(Constants.ISSUER_X500_NAME))
        val myInfo = memberLookup.myInfo()
        if(null == issuer){
            throw CordaRuntimeException("No issuer found")
        }
        states.forEach{ it: StateAndRef<Token>? -> log.warn("token ${it?.state?.contractState}")}

        val filtered = states.filter{it -> it.state.contractState.ownerHash == toSecureHash(myInfo.name, digestService)}
        val results = filtered.map {
            TokensResult(
                it.state.contractState.currency,
                it.state.contractState.symbol,
                it.state.contractState.ownerHash,
                it.state.contractState.value) }
        results.forEach{ it:TokensResult -> log.warn("token ${it.symbol}")}
        //val result = results.singleOrNull {it.id == flowArgs.id}
        //    ?: throw CordaRuntimeException("Did not find an unique unconsumed Account with id ${flowArgs.id}")

        return jsonMarshallingService.format(results)

    }
}

class GetTokensFlowInternal(private val owner: String) : SubFlow<List<StateAndRef<Token>>> {

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var digestService: DigestService

    @Suspendable
    override fun call(): List<StateAndRef<Token>>{
        val states: List<StateAndRef<Token>> = ledgerService.findUnconsumedStatesByType(Token::class.java)
        val result = states.filter {it:StateAndRef<Token> -> it.state.contractState.ownerHash == toSecureHash(owner, digestService)}
            ?: throw CordaRuntimeException("Did not find an unique unconsumed Token state for member $owner")

        return result

    }
}