package fi.kela.contracts

import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.ledger.utxo.Command
import net.corda.v5.ledger.utxo.Contract
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction
import org.slf4j.LoggerFactory

class TokenContract : Contract {

    class Issue : Command
    class Transfer : Command

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun verify(transaction: UtxoLedgerTransaction) {
        val command: Command = transaction.commands.singleOrNull() ?: throw CordaRuntimeException("Requires a single command.")
        log.warn("verifying token issuance, command: $command")
    }
}