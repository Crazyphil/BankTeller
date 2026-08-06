package it.kapfer.bankteller.enablebanking

import it.kapfer.bankteller.database.BankTellerDatabase

fun buildEnableBankingClient(database: BankTellerDatabase): EnableBankingClient =
    EnableBankingClient(DatabaseEnableBankingCredentialProvider(database))
