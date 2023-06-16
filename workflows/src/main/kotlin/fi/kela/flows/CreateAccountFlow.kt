package fi.kela.flows


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
import net.corda.v5.ledger.utxo.StateAndRef
import java.time.Instant
import java.time.temporal.ChronoUnit

class CreateAccountRequest (val tagName: String?, val tagValue: String?)

@InitiatingFlow(protocol = "create-account-flow")
class CreateAccountFlow  () : ClientStartableFlow {

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
    override fun call(requestBody: ClientRequestBody): String {
        val req = requestBody.getRequestBodyAs(jsonMarshallingService, CreateAccountRequest::class.java)
        val tagName = req.tagName;
        val tagValue = req.tagValue;
        val myInfo = memberLookup.myInfo()
        log.warn("*** OURIDENTITY ***")
        log.warn(myInfo.name.toString());
        val issuers = memberLookup.lookup(MemberX500Name.parse(ISSUER_X500_NAME))
        log.warn("*** ISSUERS ***")
        log.warn(issuers?.name.toString());
        if (null == issuers) {
            throw CordaRuntimeException("No issuer found in ledger")
        }
        var existing: List<StateAndRef<AccountState>>? = null
        log.info("tagName $tagName tagValue $tagValue")
        if (null != tagName && null != tagValue) {
            log.info("fetching existing accounts");
            existing = flowEngine.subFlow(GetAccountByTagFlow(tagName = tagName, tagValue = tagValue))
            log.warn("Subflow result $existing");
        }
        try {
            if (null != existing && existing.isEmpty()) {

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
                //val notary = Party(notaryInfo.name, notaryKey)
                //val issuer = Party(issuers.name, issuers.ledgerKeys.first())
                log.warn("issuer $issuers.name");
                val outputState = AccountState(
                    id = UUID.randomUUID(),
                    description = "description",
                    issuer = issuers.name,
                    tags = tags,
                    participants = listOf(myInfo.ledgerKeys.first(), issuers.ledgerKeys.first())
                )
                log.warn("outputState built")
                val txBuilder = ledgerService.createTransactionBuilder()
                    .setNotary(notaryInfo.name)
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
                finalizedSignedTransaction.transaction.id.toString().also {
                    log.info("Success! Response: $it")
                }
                val response = session.receive(String::class.java)
                // check response for something
                return outputState.id.toString()
            } else {
                log.warn("existing account, accountId: ${existing?.get(0)?.state?.contractState?.id.toString()}");

                return existing?.get(0)?.state?.contractState?.id.toString()
            }
        } catch (e: Exception) {
            log.error("error $e")
        }

        //session.send("testing flow")
        return "Hello best wishes from ${myInfo.name}"

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
        val response =  "Hello ${session.counterparty.commonName}, best wishes from ${ourIdentity.commonName}"
        // Log the response to be sent.
        CreateAccountFlowResponder.log.info("MFF: response.message: ${response}")

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
