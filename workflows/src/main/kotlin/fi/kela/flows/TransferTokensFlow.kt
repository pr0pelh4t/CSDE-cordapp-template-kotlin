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

class TransferBody(val from: MemberX500Name?, val to:MemberX500Name, val fromAccount: String?, val receiverAccount: String, val amount: Int, val symbol: String)

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
        var tokenClaim: TokenClaim? = null
        if(null == reqBody.fromAccount){
            log.info("no from account, getting from token pool");

            val issuer = memberLookup.lookup(MemberX500Name.parse(Constants.ISSUER_X500_NAME))
            val notary: NotaryInfo = notaryLookup.notaryServices.single()
            val to = reqBody.to;
            val receiverAccount = reqBody.receiverAccount;
            val receiverNode = memberLookup.lookup(to);
            val symbol = reqBody.symbol;
            val ourIdentity = memberLookup.myInfo()

            log.warn("receiverAccount: $receiverAccount, to: $to, symbol: $symbol, amount: ${reqBody.amount}")

            if(null == receiverNode) {
                throw CordaRuntimeException("Receiver node is required for this flow")
            }

            //if((null == issuer || issuer.name != ourIdentity.name)) {
            if(null == issuer) {
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

            tokenClaim = tokenSelection.tryClaim(selectionCriteria);
            if(tokenClaim == null) {
                return "FAILED TO FIND ENOUGH TOKENS"
            }

            log.warn("*** TOKEN CLAIM ***")
            log.warn(tokenClaim.toString())
            tokenClaim.claimedTokens.forEach{it -> log.info("claimed ${it}")}

            var spentCoins = listOf<StateRef>()

            try{
                //spentCoins = transfer(tokenClaim.claimedTokens, receiverAccount, receiverNode, notary.name, listOf(receiverNode.ledgerKeys.first()))
                //tokenClaim.useAndRelease()))
                // dirty hack, after transactions tokenclaim does not exist anymore
                //tokenClaim.useAndRelease(tokenClaim.claimedTokens.map{it -> it.stateRef})
                spentCoins = transfer(tokenClaim.claimedTokens, receiverAccount, receiverNode, notary.name, listOf(receiverNode.ledgerKeys.first()))
                /*tokenClaim.claimedTokens.forEach{ it ->
                    log.info("token, $it");

                    /** We need the initial state of the token */
                    val inputState:StateRef = it.stateRef
                    log.info("inputState: $inputState")
                    log.info("ledgerService $ledgerService")
                    //val opt2: List<StateAndRef<Token>> = ledgerService.findUnconsumedStatesByType(Token::class.java);
                    //opt2.forEach{tkn -> log.info("tkn: $tkn")}
                    val ipt: StateAndRef<Token> = ledgerService.resolve(inputState)

                    /** We need the initial state of the receiver account */
                    val rcvAccount = flowEngine.subFlow(GetAccountFlowInternal(receiverAccount))
                    val inputAccState: TransactionState<AccountState> = rcvAccount.state

                    /** Construct output (new) state of the token */
                    val outputState = ipt.state.contractState.transfer( newOwner = toSecureHash(receiverAccount, digestService), newParticipants = listOf(receiverNode.ledgerKeys.first()))

                    /** Construct output state of the account (for each token added to account) */
                    val accOtptState = inputAccState.contractState.modifyBalance(symbol = ipt.state.contractState.symbol, amount = BigDecimal(1) )

                    val txBuilder2: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                        //.setNotary(ipt.state.notary.name)
                        .setNotary(notary.name)
                        .addInputState(ipt.ref)
                        .addCommand(TokenContract.Transfer())
                        .addOutputState(outputState)
                        .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                        .addSignatories(listOf(receiverNode.ledgerKeys.first()))



                    val stx1 = txBuilder2.toSignedTransaction();
                    val session:FlowSession = flowMessaging.initiateFlow(receiverNode.name)
                    val finalizedSignedTx = ledgerService.finalize(stx1, listOf(session))


                    log.warn("notary $notary ,  accnotary: ${rcvAccount.state}")

                    /** Construct transaction */
                    val txBuilder: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                        .setNotary(notary.name)
                        //.setNotary(rcvAccount.state.notary.name)
                        //.addInputState(inputState)
                        .addInputState(rcvAccount.ref)
                        .addCommand(AccountContract.Update())
                        //.addOutputState(outputState)
                        .addOutputState(accOtptState)
                        .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                        .addSignatories(listOf(receiverNode.ledgerKeys.first()))

                    val signedTx = txBuilder.toSignedTransaction()
                    val session2:FlowSession = flowMessaging.initiateFlow(receiverNode.name)
                    val finalizedSignedTx2 = ledgerService.finalize(signedTx, listOf(session2))

                    /** Testing if this marks token as used */
                    spentCoins.add(it.stateRef)
                }*/
                //tokenClaim.useAndRelease(spentCoins)

            }catch(e:Exception){
                log.warn("Error", e);
            }
            finally{
                log.info("spentCoins ${spentCoins.size}")
                spentCoins.forEach{it -> log.info("spent ${it}")}
                tokenClaim.useAndRelease(spentCoins)
            }
        }else{
            val issuer = memberLookup.lookup(MemberX500Name.parse(Constants.ISSUER_X500_NAME))
            val notary: NotaryInfo = notaryLookup.notaryServices.single()
            val to = reqBody.to;
            val receiverAccount = reqBody.receiverAccount;
            val fromAccount = reqBody.fromAccount;
            val receiverNode = memberLookup.lookup(to);
            val symbol = reqBody.symbol;
            val ourIdentity = memberLookup.myInfo()

            log.warn("receiverAccount: $receiverAccount, to: $to, symbol: $symbol, amount: ${reqBody.amount}")

            if(null == receiverNode) {
                throw CordaRuntimeException("Receiver node is required for this flow")
            }

            //if((null == issuer || issuer.name != ourIdentity.name)) {
            if(null == issuer) {
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

            tokenClaim = tokenSelection.tryClaim(selectionCriteria);
            if(tokenClaim == null) {
                return "FAILED TO FIND ENOUGH TOKENS"
            }

            log.warn("*** TOKEN CLAIM ***")
            log.warn(tokenClaim.toString())
            tokenClaim.claimedTokens.forEach{it -> log.info("claimed ${it}")}

            var spentCoins = listOf<StateRef>()

            try{
                spentCoins = transfer(tokenClaim.claimedTokens, receiverAccount, receiverNode, notary.name, listOf(receiverNode.ledgerKeys.first()))
            }catch(e:Exception){
                log.warn("Error", e);
            }
            finally{
                log.info("spentCoins ${spentCoins.size}")
                spentCoins.forEach{it -> log.info("spent ${it}")}
                tokenClaim.useAndRelease(spentCoins)
            }
        }
        return "ok"
    }

    @Suspendable
    private fun transfer(claimedTokens: List<ClaimedToken>, receiverAccount: String, otherNode: MemberInfo, notary: MemberX500Name, newParticipants: List<PublicKey>): List<StateRef>{
        log.info("in transfer fun")
        val output = mutableListOf<StateRef>();
        //val sessions = mutableListOf<FlowSession>()

        val tokenOutputStates = mutableListOf<Token>();

        claimedTokens.forEach { it ->
            log.info("token, $it");
            val inputState:StateRef = it.stateRef
            val ipt: StateAndRef<Token> = ledgerService.resolve(inputState)
            /** Construct output (new) state of the token */
            val outputState: Token = ipt.state.contractState.transfer( newOwner = toSecureHash(receiverAccount, digestService), newParticipants = newParticipants)
            tokenOutputStates.add(outputState);
        }
            /** We need the initial state of the token */
            //val inputState:StateRef = it.stateRef
            //log.info("inputState: $inputState")
            log.info("ledgerService $ledgerService")
            //val opt2: List<StateAndRef<Token>> = ledgerService.findUnconsumedStatesByType(Token::class.java);
            //opt2.forEach{tkn -> log.info("tkn: $tkn")}
            //val ipt: StateAndRef<Token> = ledgerService.resolve(inputState)

            /** We need the initial state of the receiver account */
            val rcvAccount = flowEngine.subFlow(GetAccountFlowInternal(receiverAccount))
            val inputAccState: TransactionState<AccountState> = rcvAccount.state


            /** Construct output state of the account (for each token added to account) */
            val accOtptState = inputAccState.contractState.modifyBalance(symbol = tokenOutputStates[0].symbol, amount = BigDecimal( claimedTokens.size ) )

            val txBuilder2: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                //.setNotary(ipt.state.notary.name)
                .setNotary(notary)
                //.addInputState(ipt.ref)
                .addInputStates(claimedTokens.map{ it -> it.stateRef})
                .addCommand(TokenContract.Transfer())
                .addOutputStates(tokenOutputStates)
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(newParticipants)

            val stx1 = txBuilder2.toSignedTransaction();
            flowEngine.subFlow(UpdateTokenFlow(stx1,  otherNode));
            //val session:FlowSession = flowMessaging.initiateFlow(otherNode.name)
            //val finalizedSignedTx = ledgerService.finalize(stx1, listOf(session))


            log.warn("notary $notary ,  accnotary: ${rcvAccount.state}")

            /** Construct transaction */
            val txBuilder: UtxoTransactionBuilder = ledgerService.createTransactionBuilder()
                .setNotary(notary)
                //.setNotary(rcvAccount.state.notary.name)
                //.addInputState(inputState)
                .addInputState(rcvAccount.ref)
                .addCommand(AccountContract.Update())
                //.addOutputState(outputState)
                .addOutputState(accOtptState)
                .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(newParticipants)

            val signedTx = txBuilder.toSignedTransaction()
            flowEngine.subFlow(UpdateAccountFlow(signedTx, otherNode))
            //val session2:FlowSession = flowMessaging.initiateFlow(otherNode.name)
            //val finalizedSignedTx2 = ledgerService.finalize(signedTx, listOf(session2))

            /** Testing if this marks token as used */
            //output.add(it.stateRef)
        output.addAll(claimedTokens.map{it -> it.stateRef})
        //}

        return output
    }

}

@InitiatingFlow(protocol = "UpdateTokenFlow")
class UpdateTokenFlow(val signedTransaction: UtxoSignedTransaction, val otherNode: MemberInfo) : SubFlow<String>{

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(): String {
        val session:FlowSession = flowMessaging.initiateFlow(otherNode.name)
        val finalizedSignedTx: FinalizationResult = ledgerService.finalize(signedTransaction, listOf(session))
        return finalizedSignedTx.transaction.id.toString();
    }
}

@InitiatedBy(protocol = "UpdateTokenFlow")
class UpdateTokenResponderFlow() : ResponderFlow{
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession){
        ledgerService.receiveFinality(session){ ledgerTransaction ->
            log.info("Finished token update responder flow - ${ledgerTransaction.id}")
        }

    }
}
@InitiatingFlow(protocol = "UpdateAccountFlow")
class UpdateAccountFlow(val utxoSignedTransaction: UtxoSignedTransaction, val otherNode: MemberInfo) : SubFlow<String>{

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(): String {
        val session:FlowSession = flowMessaging.initiateFlow(otherNode.name)
        val finalizedSignedTx = ledgerService.finalize(utxoSignedTransaction, listOf(session))
        return finalizedSignedTx.transaction.id.toString();

    }
}
@InitiatedBy(protocol = "UpdateAccountFlow")
class UpdateAccountResponderFlow() : ResponderFlow{

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService
    @Suspendable
    override fun call(session: FlowSession) {
        ledgerService.receiveFinality(session){ ledgerTransaction ->
            log.info("Finished Account update responder flow - ${ledgerTransaction.id}")
        }
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