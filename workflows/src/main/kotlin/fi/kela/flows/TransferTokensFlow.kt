package fi.kela.flows

import fi.kela.contracts.AccountContract
import fi.kela.contracts.TokenContract
import fi.kela.states.AccountState
import fi.kela.states.Token
import fi.kela.utils.Constants
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.utxo.StateRef
import net.corda.v5.ledger.utxo.token.selection.TokenClaimCriteria
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import fi.kela.utils.Utils.Companion.toSecureHash
import net.corda.v5.application.crypto.DigestService
import net.corda.v5.application.flows.*
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.utxo.FinalizationResult
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.TransactionState
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.token.selection.ClaimedToken
import net.corda.v5.ledger.utxo.token.selection.TokenClaim
import net.corda.v5.ledger.utxo.token.selection.TokenSelection
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import net.corda.v5.ledger.utxo.transaction.UtxoTransactionBuilder
import net.corda.v5.membership.MemberInfo
import net.corda.v5.membership.NotaryInfo
import java.security.PublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit

//class TransferBody(val from: MemberX500Name?, val to:MemberX500Name, val fromAccount: String?, val receiverAccount: String, val amount: Int, val symbol: String)
class TransferBody(val from: MemberX500Name?, val to: MemberX500Name, val amount: Int, val symbol: String)

/**
 * Flow to transfer tokens from one owner to another
 */
@InitiatingFlow(protocol = "TransferTokensFlow")
class TransferTokensFlow : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var tokenSelection: TokenSelection

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var digestService: DigestService

    @Suspendable
    override fun call(requestBody: ClientRequestBody): String {
        val reqBody: TransferBody = requestBody.getRequestBodyAs(jsonMarshallingService, TransferBody::class.java)
        var tokenClaim: TokenClaim? = null
        if (null == reqBody.from) {
            throw CordaRuntimeException("From node is required for this flow")
        }
        val issuer = memberLookup.lookup(MemberX500Name.parse(Constants.ISSUER_X500_NAME))
        val notary: NotaryInfo = notaryLookup.notaryServices.single()
        val to = reqBody.to;
        val from = reqBody.from;

        val fromNode =
            memberLookup.lookup(from) ?: throw CordaRuntimeException("Unable to determine sender node");
        val receiverNode = memberLookup.lookup(to);
        val symbol = reqBody.symbol;
        val ourIdentity = memberLookup.myInfo()

        log.warn("receiverNode: $receiverNode, to: $to, symbol: $symbol, amount: ${reqBody.amount}")

        if (null == receiverNode) {
            throw CordaRuntimeException("Receiver node is required for this flow")
        }

        if (null == issuer) {
            throw CordaRuntimeException("Only an issuer can initiate this flow.")
        }

        log.warn(
            "symbol: $symbol, issuerHash: ${
                toSecureHash(
                    issuer.name,
                    digestService
                )
            }, notary: ${notary.name}, amount: ${reqBody.amount}"
        )

        val issuerHash = toSecureHash(issuer.name, digestService)
        val selectionCriteria = TokenClaimCriteria(
            symbol,
            issuerHash,
            notary.name,
            "EUR",
            BigDecimal(reqBody.amount)
        )

        tokenClaim = tokenSelection.tryClaim(selectionCriteria);
        if (tokenClaim == null) {
            return "FAILED TO FIND ENOUGH TOKENS"
        }

        log.warn("*** TOKEN CLAIM ***")
        log.warn(tokenClaim.toString())
        tokenClaim.claimedTokens.forEach { it -> log.info("claimed ${it}") }

        var spentCoins = listOf<StateRef>()

        try {
            spentCoins = transfer(
                tokenClaim.claimedTokens,
                reqBody.amount,
                symbol,
                fromNode,
                receiverNode,
                issuerHash,
                notary.name,
                listOf(ourIdentity.ledgerKeys.first(), receiverNode.ledgerKeys.first())
            )
        } catch (e: Exception) {
            log.warn("Error", e);
        } finally {
            log.info("spentCoins ${spentCoins.size}")
            spentCoins.forEach { it -> log.info("spent ${it}") }
            tokenClaim.useAndRelease(spentCoins)
        }
        return "ok"
    }

    @Suspendable
    //private fun transfer(claimedTokens: List<ClaimedToken>, receiverAccount: String, otherNode: MemberInfo, notary: MemberX500Name, newParticipants: List<PublicKey>): List<StateRef>{
    private fun transfer(
        claimedTokens: List<ClaimedToken>,
        amount: Int,
        symbol: String,
        senderNode: MemberInfo,
        receiverNode: MemberInfo,
        issuerHash: SecureHash,
        notary: MemberX500Name,
        newParticipants: List<PublicKey>
    ): List<StateRef> {

        log.info("in transfer fun")
        val output = mutableListOf<StateRef>();
        //val sessions = mutableListOf<FlowSession>()

        val tokenOutputStates = mutableListOf<Token>();
        val totalAmount = claimedTokens.stream().map { it -> it.amount }.reduce(BigDecimal.ZERO, BigDecimal::add);
        log.warn("totalAmount: $totalAmount")
        val change = totalAmount.subtract(BigDecimal(amount))

        val newState = Token(
            issuer = issuerHash,
            ownerHash = toSecureHash(receiverNode.name, digestService),
            participants = listOf(receiverNode.ledgerKeys.first()),
            nominalValue = BigDecimal(1),
            currency = "EUR",
            symbol = symbol,
            value = BigDecimal(amount)
        )
        tokenOutputStates.add(newState);
        /*
        claimedTokens.forEach { it ->
            log.info("token, $it");
            val inputState: StateRef = it.stateRef
            //val amount = it.amount
            val ipt: StateAndRef<Token> = ledgerService.resolve(inputState)

            /** Construct output (new) state of the token */
            //val outputState: Token = ipt.state.contractState.transfer( newOwner = toSecureHash(receiverAccount, digestService), newParticipants = newParticipants)
            val newState: Token = ipt.state.contractState.transfer(
                newOwner = toSecureHash(receiverNode.name, digestService),
                newParticipants = listOf(receiverNode.ledgerKeys.first()),
                transferValue = BigDecimal(amount)
            )
            log.info("*** newState $newState ***")
            tokenOutputStates.add(newState);
        }
        */
        if (change.compareTo(BigDecimal.ZERO) > 0) {
            // if there is remaining change, create a new gold state representing the original sender and the change
            val changeState = Token(
                symbol = "HNT",
                currency = "EUR",
                value = change,
                nominalValue = BigDecimal(1),
                participants = listOf(senderNode.ledgerKeys.first()),
                issuer = issuerHash,
                ownerHash = toSecureHash(senderNode.name, digestService)
            )
            tokenOutputStates.add(changeState);
        }

        val txBuilder2: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
            //.setNotary(ipt.state.notary.name)
            .setNotary(notary)
            //.addInputState(ipt.ref)
            .addInputStates(claimedTokens.map { it -> it.stateRef })
            .addCommand(TokenContract.Transfer())
            .addOutputStates(tokenOutputStates)
            .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
            .addSignatories(newParticipants)

        val stx1 = txBuilder2.toSignedTransaction();
        flowEngine.subFlow(UpdateTokenFlow(stx1, receiverNode));
        output.addAll(claimedTokens.map { it -> it.stateRef })
        //}

        return output
    }

}

@InitiatingFlow(protocol = "UpdateTokenFlow")
class UpdateTokenFlow(val signedTransaction: UtxoSignedTransaction, val otherNode: MemberInfo) : SubFlow<String> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(): String {
        val session: FlowSession = flowMessaging.initiateFlow(otherNode.name)
        val finalizedSignedTx: FinalizationResult = ledgerService.finalize(signedTransaction, listOf(session))
        return finalizedSignedTx.transaction.id.toString();
    }
}

@InitiatedBy(protocol = "UpdateTokenFlow")
class UpdateTokenResponderFlow() : ResponderFlow {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession) {
        ledgerService.receiveFinality(session) { ledgerTransaction ->
            log.info("Finished token update responder flow - ${ledgerTransaction.id}")
        }

    }
}

@InitiatingFlow(protocol = "UpdateAccountFlow")
class UpdateAccountFlow(val utxoSignedTransaction: UtxoSignedTransaction, val otherNode: MemberInfo) : SubFlow<String> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(): String {
        val session: FlowSession = flowMessaging.initiateFlow(otherNode.name)
        val finalizedSignedTx = ledgerService.finalize(utxoSignedTransaction, listOf(session))
        return finalizedSignedTx.transaction.id.toString();

    }
}

@InitiatedBy(protocol = "UpdateAccountFlow")
class UpdateAccountResponderFlow() : ResponderFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession) {
        ledgerService.receiveFinality(session) { ledgerTransaction ->
            log.info("Finished Account update responder flow - ${ledgerTransaction.id}")
        }
    }
}

@InitiatedBy(protocol = "TransferTokensFlow")
class TransferRespondingFlow() : ResponderFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    override fun call(session: FlowSession) {
        ledgerService.receiveFinality(session) { ledgerTransaction ->
            log.info("Finished token transfer responder flow - ${ledgerTransaction.id}")
        }

    }
}