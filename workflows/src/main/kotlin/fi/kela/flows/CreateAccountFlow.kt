package fi.kela.flows


import com.r3.developers.csdetemplate.flowexample.workflows.Message
import fi.kela.contracts.AccountContract
import fi.kela.states.AccountState
import fi.kela.states.Tag
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import java.util.UUID;
import fi.kela.utils.Constants.Companion.ISSUER_X500_NAME;
import net.corda.v5.application.flows.*
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.StateAndRef
import java.time.Instant
import java.time.temporal.ChronoUnit

class CreateAccountRequest ()

@InitiatingFlow(protocol = "create-account-flow")
class CreateAccountFlow  (private val tagName: String? = null, private val tagValue: String? = null) : ClientStartableFlow {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    // FlowMessaging provides a service for establishing flow sessions between Virtual Nodes and
    // sending and receiving payloads between them
    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    // Injects the UtxoLedgerService to enable the flow to make use of the Ledger API.
    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    // FlowEngine service is required to run SubFlows.
    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call(requestBody: ClientRequestBody):String{
        val req = requestBody.getRequestBodyAs(jsonMarshallingService, CreateAccountRequest::class.java)
        val myInfo = memberLookup.myInfo()
        log.warn("*** OURIDENTITY ***")
        log.warn(myInfo.name.toString());
        val issuers = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))
        log.warn("*** ISSUERS ***")
        log.warn(issuers?.name.toString());
        if(null == issuers){
            throw CordaRuntimeException("No issuer found in ledger")
        }
        var test: List<StateAndRef<AccountState>>? = null
        if(issuers.name.toString() == myInfo.name.toString() ){
            test = flowEngine.subFlow(GetAccountByTagFlow(tagName = "issuer", tagValue = "issuer"))
            log.warn("Subflow result $test");
        }
        try {
            if ((issuers.name.toString() == myInfo.name.toString() && test?.isEmpty() == true) || issuers.name.toString() !== myInfo.name.toString()) {

                val accountId = UUID.randomUUID().toString()
                val createdTag = Tag("created", Instant.now().toString().replace(':', '.'))
                var inputTag: Tag
                var tags: List<Tag> = resolveTags(createdTag);
                if (tagName != null && tagValue != null) {
                    inputTag = Tag(tagName, tagValue)
                    tags += inputTag
                }
                log.warn("### TAGS: $tags")
                val notaryInfo = notaryLookup.notaryServices.single()
                val notaryKey = memberLookup.lookup().single {
                    it.memberProvidedContext["corda.notary.service.name"] == notaryInfo.name.toString()
                }.ledgerKeys.first()
                val notary = Party(notaryInfo.name, notaryKey)
                val issuer = Party(issuers.name, issuers.ledgerKeys.first())
                log.warn("issuer $issuer");
                val outputState = AccountState(
                    id = UUID.randomUUID(),
                    description = "description",
                    issuer = issuer.name,
                    tags = listOf(Tag("test", "test")),
                    participants = listOf(issuer.owningKey, myInfo.ledgerKeys.first())
                )
                log.warn("outputState built")
                val txBuilder = ledgerService.getTransactionBuilder()
                    .setNotary(notary)
                    .addOutputState(outputState)
                    .addCommand(AccountContract.Request())
                    .setTimeWindowBetween(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                    .addSignatories(outputState.participants)
                log.warn("transaction built")
                val signedTransaction = txBuilder.toSignedTransaction()
                log.warn("signed transaction built")
                val session = flowMessaging.initiateFlow(MemberX500Name.parse(ISSUER_X500_NAME))
                val finalizedSignedTransaction = ledgerService.finalize(
                    signedTransaction,
                    listOf(session)
                )
                finalizedSignedTransaction.id.toString().also {
                    log.info("Success! Response: $it")
                }
                val response: Message = session.receive(Message::class.java)
                // check response for something
                return outputState.id.toString()
            } else {
                log.warn("issuer node, existing account");
                return test?.get(0)?.state?.contractState?.id.toString()
            }
        }catch(e:Exception){
            log.error("error $e")
        }

        //session.send("testing flow")
        val response = Message(myInfo.name,
            "Hello best wishes")
        return response.message

    }

    private fun resolveTags(createdTag:Tag) : List<Tag> {

        val ourIdentity = memberLookup.myInfo().name
        val issuer = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))

        if (issuer?.name == ourIdentity) {
            return listOf(Tag("issuer", "issuer"), createdTag) // TODO: change to properties reference
        }
        return listOf(createdTag)
    }
}

@InitiatedBy(protocol = "create-account-flow")
// Responder flows must inherit from ResponderFlow
class CreateAccountFlowResponder: ResponderFlow {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession) {
        // Get our identity from the MemberLookup service.
        val ourIdentity = memberLookup.myInfo().name
        log.warn("in responder flow, current identity: $ourIdentity")
        try{
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
        session.send(response)
    }
}

/*
RequestBody for triggering the flow via REST:
{
    "clientRequestId": "create-1",
    "flowClassName": "fi.kela.flows.CreateAccountFlow",
    "requestBody": {}
}
 */
