package fi.kela.flows

import com.r3.developers.csdetemplate.flowexample.workflows.Message
import fi.kela.states.AccountState
import fi.kela.states.Tag
import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.UtxoLedgerService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@InitiatingFlow(protocol = "get-account-by-tag-flow")
class GetAccountByTagFlow(tagName: String, tagValue: String) : SubFlow<List<StateAndRef<AccountState>>> {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @CordaInject
    lateinit var ledgerService: UtxoLedgerService

    @Suspendable
    override fun call(): List<StateAndRef<AccountState>> {

        val notaryInfo = notaryLookup.notaryServices.single()
        val notaryKey = memberLookup.lookup().single {
            it.memberProvidedContext["corda.notary.service.name"] == notaryInfo.name.toString()
        }.ledgerKeys.first()

        /**
         * Gets all existing accounts
         */
        val existingAccounts = ledgerService.findUnconsumedStatesByType<AccountState>(AccountState::class.java)
        log.warn("existing accounts")
        log.warn(existingAccounts.toString())

        return existingAccounts
    }
}

@InitiatedBy(protocol = "get-account-by-tag-flow")
// Responder flows must inherit from ResponderFlow
class GetAccountByTagFlowResponder: ResponderFlow {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @Suspendable
    override fun call(session: FlowSession) {
        // Get our identity from the MemberLookup service.
        val ourIdentity = memberLookup.myInfo().name

        // Create a response to greet the sender
        val response = Message(ourIdentity,
            "Hello ${session.counterparty.commonName}, best wishes from ${ourIdentity.commonName}")

        // Log the response to be sent.
        GetAccountByTagFlowResponder.log.info("MFF: response.message: ${response.message}")

        // Send the response via the send method on the flow session
        session.send(response)
    }
}
