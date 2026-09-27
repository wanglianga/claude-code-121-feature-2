package com.pharmacy.coldchain

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ColdChainApplication

fun main(args: Array<String>) {
    runApplication<ColdChainApplication>(*args)
}
