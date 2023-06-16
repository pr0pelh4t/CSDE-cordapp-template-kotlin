package fi.kela.utils

import net.corda.v5.application.crypto.DigestService
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash

class Utils {

    companion object {



        fun toSecureHash(name: MemberX500Name, digestService: DigestService): SecureHash {
            return digestService.hash(name.toString().toByteArray(), DigestAlgorithmName.SHA2_256)
        }

        fun toSecureHash(string: String, digestService: DigestService): SecureHash {
            return digestService.hash(string.toByteArray(), DigestAlgorithmName.SHA2_256)
        }
    }

}