package fi.kela.states

import fi.kela.contracts.AccountContract
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.ledger.utxo.BelongsToContract
import net.corda.v5.ledger.utxo.ContractState
import java.math.BigDecimal
import java.security.PublicKey
import java.util.UUID

@BelongsToContract(AccountContract::class)
class AccountState (
    val id: UUID,
    val description: String,
    val issuer: MemberX500Name,
    val tags: List<Tag>,
    val balances: List<Balance> = listOf(),
    private val participants: List<PublicKey>
) : ContractState {
    override fun getParticipants(): List<PublicKey> {
        return participants
    }

    /** Helper function to change balance */
    fun modifyBalance(symbol: String, amount: BigDecimal): AccountState{
        val existingBalances: MutableList<Balance> = mutableListOf<Balance>()
        existingBalances.addAll(balances)
        var currentBalance: Balance? = existingBalances.singleOrNull{ it -> it.symbol == symbol }
        if(null==currentBalance){
            existingBalances.add(Balance(symbol = symbol, amount = amount))
        }
        else{
            val idx = existingBalances.indexOf(currentBalance)
            currentBalance = Balance(symbol = currentBalance.symbol, amount = currentBalance.amount.add(amount))
            existingBalances.removeAt(idx);
            existingBalances.add(currentBalance);
        }
        return AccountState(id = id, description = description, issuer = issuer, tags = tags, balances = existingBalances, participants = participants)
    }



}