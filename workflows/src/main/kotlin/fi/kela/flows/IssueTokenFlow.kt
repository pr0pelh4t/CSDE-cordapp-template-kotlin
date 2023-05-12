package fi.kela.flows

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
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.token.selection.TokenSelection
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import net.corda.v5.ledger.utxo.transaction.UtxoTransactionBuilder
import net.corda.v5.membership.NotaryInfo
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit


/**
 * A flow that issue tokens.
 */
class IssueTokenFlow : SubFlow<String> {

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
    override fun call() : String{
        return try {
            //val kela = memberLookup.lookup(MemberX500Name.parse(KELA_X500_NAME));
            val issuer = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))
            val ourIdentity = memberLookup.myInfo()

            if((null == issuer || issuer.name != ourIdentity.name)) {
                throw CordaRuntimeException("Only an issuer can initiate this flow.")
            }

            /*if((null==kela)) {
                throw CordaRuntimeException("Need Kela in this flow.")
            }*/

            val notary: NotaryInfo = notaryLookup.notaryServices.single()

            /** Initial state of our token */
            val mintToken = Token(
                currency = "EUR",
                value= BigDecimal.valueOf(1),
                nominalValue = BigDecimal.valueOf(1),
                symbol= "HNT",
                issuer = issuer.name.toSecureHash(),
                participants = listOf(issuer.ledgerKeys.first())
            );
            val issuance: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                .setNotary(notary.name)
                //.addOutputStates(List(100) { mintToken })
                .addOutputStates(mintToken)
                .addCommand(TokenContract.Issue())
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(mintToken.participants)

            val signedIssuance = issuance.toSignedTransaction();
            log.warn("issuance signed");
            val session = flowEngine.subFlow(FinalizeTokenIssueSubFlow(signedIssuance, listOf(ourIdentity.name)))

            return "Success?"
        } catch (ex: Exception) {
            return "Error $ex"
        }
    }

    fun MemberX500Name.toSecureHash():SecureHash{
        return digestService.hash(toString().toByteArray(), DigestAlgorithmName.SHA2_256)
    }
}

@InitiatingFlow(protocol = "finalize-token-issue-flow")
// Responder flows must inherit from ResponderFlow
class FinalizeTokenIssueSubFlow(private val signedTransaction: UtxoSignedTransaction, private val otherMember: List<MemberX500Name>): SubFlow<String> {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @Suspendable
    override fun call() : String {
        // Get our identity from the MemberLookup service.
        val ourIdentity = memberLookup.myInfo().name
        log.warn("in subflow, current identity: $ourIdentity")
        val sessions = otherMember.map { flowMessaging.initiateFlow(it) }
        //val session = flowMessaging.initiateFlow(MemberX500Name.parse(ISSUER_X500_NAME))
        return try {
            // Calls the Corda provided finalise() function which gather signatures from the counterparty,
            // notarises the transaction and persists the transaction to each party's vault.
            // On success returns the id of the transaction created.
            val finalizedSignedTransaction = ledgerService.finalize(
                signedTransaction,
                //listOf(session)
                sessions
            )
            // Returns the transaction id converted to a string.
            finalizedSignedTransaction.transaction.id.toString().also {
                log.info("Success! Response: $it")
            }
        }
        // Soft fails the flow and returns the error message without throwing a flow exception.
        catch (e: Exception) {
            log.warn("Finality failed", e)
            "Finality failed, ${e.message}"
        }
        /*try{
            ledgerService.receiveFinality(session){transaction ->
                log.warn("received finality $transaction")
            }}
        catch(e:Exception){
            log.error("failed due to ", e.message)
        }

        // Create a response to greet the sender
        val response = Message(ourIdentity,
            "Hello ${session.counterparty.commonName}, best wishes from ${ourIdentity.commonName}")

        // Log the response to be sent.
        CreateAccountFlowResponder.log.info("MFF: response.message: ${response.message}")

        // Send the response via the send method on the flow session
        session.send(response)*/
    }
}

@InitiatedBy(protocol = "finalize-token-issue-flow")
class FinalizeTokenIssueSubResponderFlow :  ResponderFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    // Injects the UtxoLedgerService to enable the flow to make use of the Ledger API.
    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession) {

        log.info("FinalizeTokenIssueSubResponderFlow.call() called")

        try {
            // Calls receiveFinality() function which provides the responder to the finalise() function
            // in the Initiating Flow. Accepts a lambda validator containing the business logic to decide whether
            // responder should sign the Transaction.
            val finalizedSignedTransaction = ledgerService.receiveFinality(session) { ledgerTransaction ->

                // Note, this exception will only be shown in the logs if Corda Logging is set to debug.
                val state = ledgerTransaction.getOutputStates(Token::class.java).singleOrNull()
                    ?: throw CordaRuntimeException("Failed verification - transaction did not have exactly one output Token state.")

                log.info("Verified the transaction- ${ledgerTransaction.id}")
            }
            log.info("Finished responder flow - ${finalizedSignedTransaction.transaction.id}")
        }
        // Soft fails the flow and log the exception.
        catch (e: Exception) {
            log.warn("Exceptionally finished responder flow", e)
        }
    }
}
