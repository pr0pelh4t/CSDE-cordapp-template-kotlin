package fi.kela.contracts

import fi.kela.states.AccountState
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.ledger.utxo.Command
import net.corda.v5.ledger.utxo.Contract
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction
import org.slf4j.LoggerFactory

class AccountContract : Contract {

    class Request : Command
    class Update : Command

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun verify(transaction: UtxoLedgerTransaction) {
        val command: Command = transaction.commands.singleOrNull() ?: throw CordaRuntimeException("Requires a single command.")
        log.warn("verifying account transaction, command: $command")
        when (command) {
            is Request -> {
                val output = transaction.outputContractStates.first() as AccountState
                "When command is Request there should be no input states." using (transaction.inputContractStates.isEmpty())
                "The output AppleStamp state should have clear description of the type of redeemable goods" using (output.description.isNotEmpty())
                "Fresh account should not have balance" using (output.balances.isEmpty())
            }
            is Update -> {
                val output = transaction.outputContractStates.first() as AccountState
                "The output Account state should have clear description of the type of redeemable goods" using (output.description.isNotEmpty())
            }
            else -> {
              throw CordaRuntimeException("Unknown command.")
            }
        }
    }
    // Helper function to allow writing constraints in the Corda 4 '"text" using (boolean)' style
    private infix fun String.using(expr: Boolean) {
        if (!expr) throw CordaRuntimeException("Failed requirement: $this")
    }

    // Helper function to allow writing constraints in '"text" using {lambda}' style where the last expression
    // in the lambda is a boolean.
    private infix fun String.using(expr: () -> Boolean) {
        if (!expr.invoke()) throw CordaRuntimeException("Failed requirement: $this")
    }
}