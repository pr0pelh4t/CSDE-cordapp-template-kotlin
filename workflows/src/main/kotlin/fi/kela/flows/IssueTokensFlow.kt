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
import net.corda.v5.ledger.utxo.StateAndRef
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

class IssueTokensRequest (val amount: Int, val newOwner: String)


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
        val newOwner = req.newOwner;
        val newOwnerNode = memberLookup.lookup(MemberX500Name.parse(newOwner));
        val notary: NotaryInfo = notaryLookup.notaryServices.single()

        log.warn("issuing $amount tokens")
        return try {
            val issuer = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))
            val ourIdentity = memberLookup.myInfo()

            if(null == issuer) {

                    throw CordaRuntimeException("Only an issuer can initiate this flow.")
            }

            if(null == newOwnerNode){
                throw CordaRuntimeException("could not find receiver node in ledger")
            }

            val tkns = mutableListOf<Token>()
            var existingTokens = listOf<StateAndRef<Token>>()
            /**
             * Check if the receiver already owns same type of tokens
             **/
            if(newOwner !== null) {
                existingTokens = flowEngine.subFlow(GetTokensFlowInternal(newOwner));

            }
            if(!existingTokens.isEmpty()) {
                /**
                 * Existing balance for the token type being issued
                 */
                log.warn("existingTokens $existingTokens tokens")
                log.warn("should update existing token value")

                val token = existingTokens.first{it -> it.state.contractState.symbol === "HNT"}
                /** Create a new state with the issuance amount */
                val output = token.state.contractState.add(BigDecimal(amount))
                val issuance: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                    .setNotary(notary.name)
                    .addInputState(token.ref)
                    .addOutputStates(output)
                    .addCommand(TokenContract.Issue())
                    .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                    .addSignatories(ourIdentity.ledgerKeys.first(), newOwnerNode.ledgerKeys.first())
                log.warn("--- Created issuance: $issuance ---")
                val signedIssuance = issuance.toSignedTransaction();
                log.warn("issuance signed");

                flowEngine.subFlow(FinalizeTokensIssueSubFlow(signedIssuance, listOf()))
                return "Success?"
            }else {
                /**
                 * Not existing balance for issued token.
                 * Create a state for a mint(new) token
                 */
                val mintToken = Token(
                    symbol = "HNT",
                    currency = "EUR",
                    value = BigDecimal(amount),
                    nominalValue = BigDecimal.valueOf(1),
                    issuer = issuer.name.toSecureHash(),
                    participants = listOf(issuer.ledgerKeys.first(), ourIdentity.ledgerKeys.first()),
                    ownerHash = newOwner?.toSecureHash()
                );
                tkns.add(mintToken);

                log.warn("minted tokens: $tkns")

                log.warn("notary: $notary")

                val issuance: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                    .setNotary(notary.name)
                    .addOutputStates(tkns)
                    .addCommand(TokenContract.Issue())
                    .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                    .addSignatories(ourIdentity.ledgerKeys.first())

                val signedIssuance = issuance.toSignedTransaction();
                log.warn("issuance signed");

                flowEngine.subFlow(FinalizeTokensIssueSubFlow(signedIssuance, listOf()))

                return "Success?"
            }
        } catch (ex: Exception) {
            return "Error $ex"
        }
    }

    fun MemberX500Name.toSecureHash():SecureHash{
        return digestService.hash(toString().toByteArray(), DigestAlgorithmName.SHA2_256)
    }

    fun String.toSecureHash():SecureHash{
        return digestService.hash(toByteArray(), DigestAlgorithmName.SHA2_256)
    }
}

@InitiatingFlow(protocol = "finalize-tokens-issue-flow")
// Responder flows must inherit from ResponderFlow
class FinalizeTokensIssueSubFlow(private val signedTransaction: UtxoSignedTransaction, private val otherMember: List<MemberX500Name>): SubFlow<String> {
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

@InitiatedBy(protocol = "finalize-tokens-issue-flow")
class FinalizeTokensIssueSubResponderFlow :  ResponderFlow {

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


