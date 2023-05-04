package fi.kela.flows

import com.r3.developers.csdetemplate.flowexample.workflows.Message
import fi.kela.contracts.TokenContract
import fi.kela.states.Token
import fi.kela.utils.Constants.Companion.ISSUER_X500_NAME
import fi.kela.utils.Constants.Companion.KELA_X500_NAME
import net.corda.v5.application.crypto.DigestService
import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.StateRef
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.token.selection.TokenClaimCriteria
import net.corda.v5.ledger.utxo.token.selection.TokenSelection
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import net.corda.v5.ledger.utxo.transaction.UtxoTransactionBuilder
import net.corda.v5.membership.NotaryInfo
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class IssueTokensRequest (val amount: Int)


/**
 * A flow that issue tokens.
 */
class IssueTokensFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @CordaInject
    lateinit var tokenSelection: TokenSelection

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var digestService: DigestService

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @Suspendable
    override fun call(requestBody: ClientRequestBody) : String{
        val req: IssueTokensRequest = requestBody.getRequestBodyAs(jsonMarshallingService, IssueTokensRequest::class.java)
        val amount = req.amount;
        log.warn("issuing $amount tokens")
        return try {
            //val kela = memberLookup.lookup(MemberX500Name.parse(KELA_X500_NAME));
            val issuer = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))
            val ourIdentity = memberLookup.myInfo()

            if((null == issuer || issuer.name != ourIdentity.name)) {
                throw CordaRuntimeException("Only an issuer can initiate this flow.")
            }

            /*if((null==kela)){
                throw CordaRuntimeException("Need Kela in this flow.")
            }*/

            for(i in  1..amount){
                log.warn("$i");
                flowEngine.subFlow(IssueTokenFlow())
            }

            //val tokenTypeStateAndRef = serviceHub.findTokenTypesIssuesByMe(symbol)
            //val tokenIssuance = TokenContract.generateIssuance(serviceHub, amount.toString(), tokenTypeStateAndRef, accountId, ourIdentity)

            val notary: NotaryInfo = notaryLookup.notaryServices.single()
            /*
            /** Initial state of our token */
            val mintToken = Token(
                    currency = "EUR",
                    value= BigDecimal.valueOf(1),
                    issuer = issuer.name.toSecureHash(),
                    participants = listOf(issuer.ledgerKeys.first(), kela.ledgerKeys.first())
            );
            val issuance: UtxoTransactionBuilder = ledgerService.transactionBuilder
                .setNotary(Party(notary.name, notary.publicKey))
                //.addOutputStates(List(100) { mintToken })
                .addOutputStates(mintToken)
                .addCommand(TokenContract.Issue())
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(mintToken.participants)

            val signedIssuance = issuance.toSignedTransaction();
            log.warn("issuance signed");
            val session = flowEngine.subFlow(FinalizeTokenIssueSubFlow(signedIssuance, listOf(ourIdentity.name, kela.name)))
            */
            /*val selectionCriteria = TokenClaimCriteria(
                Token.tokenType,
                issuer.name.toSecureHash(),
                notary.name,
                "EUR",
                BigDecimal(10)
            )

            val tokenClaim = tokenSelection.tryClaim(selectionCriteria);

            if(tokenClaim == null) {
                return "FAILED TO FIND ENOUGH TOKENS"
            }

            log.warn("*** TOKEN CLAIM ***")
            log.warn(tokenClaim.toString())

            var spentCoins = listOf<StateRef>()*/
            return "Success?"
        } catch (ex: Exception) {
            return "Error $ex"
        }
    }

    fun MemberX500Name.toSecureHash():SecureHash{
        return digestService.hash(toString().toByteArray(), DigestAlgorithmName.SHA2_256)
    }
}

