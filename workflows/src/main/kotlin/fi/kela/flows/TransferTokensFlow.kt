package fi.kela.flows

import fi.kela.contracts.AccountContract
import fi.kela.contracts.TokenContract
import fi.kela.states.AccountState
import fi.kela.states.Balance
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
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.TransactionState
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.token.selection.ClaimedToken
import net.corda.v5.ledger.utxo.token.selection.TokenSelection
import net.corda.v5.ledger.utxo.transaction.UtxoTransactionBuilder
import net.corda.v5.membership.MemberInfo
import net.corda.v5.membership.NotaryInfo
import java.security.PublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit

class TransferBody(val from: MemberX500Name?, val to:MemberX500Name, val receiverAccount: String, val amount: Int, val symbol: String)

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
        val reqBody:TransferBody = requestBody.getRequestBodyAs(jsonMarshallingService, TransferBody::class.java)
        if(null == reqBody.from){
            log.info("no from account, getting from token pool");

            val issuer = memberLookup.lookup(MemberX500Name.parse(Constants.ISSUER_X500_NAME))
            val notary: NotaryInfo = notaryLookup.notaryServices.single()
            val to = reqBody.to;
            val receiverAccount = reqBody.receiverAccount;
            val receiverNode = memberLookup.lookup(to)
            val symbol = reqBody.symbol;
            val ourIdentity = memberLookup.myInfo()

            log.warn("receiverAccount: $receiverAccount, to: $to, symbol: $symbol, amount: ${reqBody.amount}")

            if(null == receiverNode) {
                throw CordaRuntimeException("Receiver node is required for this flow")
            }

            if((null == issuer || issuer.name != ourIdentity.name)) {
                throw CordaRuntimeException("Only an issuer can initiate this flow.")
            }

            log.warn("symbol: $symbol, issuerHash: ${toSecureHash(issuer.name, digestService)}, notary: ${notary.name}, amount: ${reqBody.amount}")

            val issuerHash = toSecureHash(issuer.name, digestService)
            val selectionCriteria = TokenClaimCriteria(
                symbol,
                issuerHash,
                notary.name,
                "EUR",
                BigDecimal(reqBody.amount)
            )
            /*val selectionCriteria = TokenClaimCriteria(
                Token.tokenType,
                toSecureHash(issuer.name, digestService),
                notary.name,
                "EUR",
                BigDecimal(reqBody.amount)
            )*/

            val tokenClaim = tokenSelection.tryClaim(selectionCriteria);

            if(tokenClaim == null) {
                return "FAILED TO FIND ENOUGH TOKENS"
            }

            log.warn("*** TOKEN CLAIM ***")
            log.warn(tokenClaim.toString())

            var spentCoins = listOf<StateRef>()

            try{
                spentCoins = transfer(tokenClaim.claimedTokens, receiverAccount, receiverNode, Party(notary.name, notary.publicKey), listOf(issuer.ledgerKeys.first(), receiverNode.ledgerKeys.first()))
            }catch(e:Exception){
                log.warn("Error", e);
            }
            finally{
                tokenClaim.useAndRelease(spentCoins)
            }
        }else{

        }
        return "ok"
    }

    @Suspendable
    private fun transfer(claimedTokens: List<ClaimedToken>, receiverAccount: String, otherNode: MemberInfo, notary: Party, newParticipants: List<PublicKey>): List<StateRef>{
        log.info("in transfer fun")
        val output = mutableListOf<StateRef>();
        //val sessions = mutableListOf<FlowSession>()
        claimedTokens.forEach{ it ->
            log.info("token, $it");

            /** We need the initial state of the token */
            val inputState:StateRef = it.stateRef
            log.info("inputState: $inputState")
            log.info("ledgerService $ledgerService")
            val opt2: List<StateAndRef<Token>> = ledgerService.findUnconsumedStatesByType(Token::class.java);
            opt2.forEach{tkn -> log.info("tkn: $tkn")}
            val ipt: StateAndRef<Token> = ledgerService.resolve(inputState)

            /** We need the initial state of the receiver account */
            val rcvAccount = flowEngine.subFlow(GetAccountFlowInternal(receiverAccount))
            val inputAccState: TransactionState<AccountState> = rcvAccount.state

            /** Construct output (new) state of the token */
            val outputState = ipt.state.contractState.transfer( newOwner = toSecureHash(receiverAccount, digestService), newParticipants = newParticipants)

            /** Construct output state of the account (for each token added to account) */
            val accOtptState = inputAccState.contractState.modifyBalance(symbol = ipt.state.contractState.symbol, amount = BigDecimal(1) )

            val txBuilder2: UtxoTransactionBuilder = ledgerService.transactionBuilder
                .setNotary(ipt.state.notary)
                //.setNotary(notary)
                .addInputState(ipt.ref)
                .addCommand(TokenContract.Transfer())
                .addOutputState(outputState)
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(newParticipants)

            val stx1 = txBuilder2.toSignedTransaction();
            val session:FlowSession = flowMessaging.initiateFlow(otherNode.name)
            val finalizedSignedTx = ledgerService.finalize(stx1, listOf(session))


            log.warn("notary $notary ,  accnotary: ${rcvAccount.state.notary}")

            /** Construct transaction */
            val txBuilder: UtxoTransactionBuilder = ledgerService.transactionBuilder
                //.setNotary(notary)
                .setNotary(rcvAccount.state.notary)
                //.addInputState(inputState)
                .addInputState(rcvAccount.ref)
                .addCommand(AccountContract.Update())
                //.addOutputState(outputState)
                .addOutputState(accOtptState)
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(newParticipants)

            val signedTx = txBuilder.toSignedTransaction()
            val session2:FlowSession = flowMessaging.initiateFlow(otherNode.name)
            val finalizedSignedTx2 = ledgerService.finalize(signedTx, listOf(session2))

            /** Testing if this marks token as used */
            output.add(it.stateRef)
        }

        return output
    }

}

@InitiatedBy(protocol = "TransferTokensFlow")
class TransferRespondingFlow() : ResponderFlow{

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    override fun call(session: FlowSession){
        ledgerService.receiveFinality(session){ ledgerTransaction ->
            log.info("Finished token transfer responder flow - ${ledgerTransaction.id}")
        }

    }
}