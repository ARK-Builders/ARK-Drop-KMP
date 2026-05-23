package dev.arkbuilders.drop.di

import dev.arkbuilders.drop.domain.usecase.ReceiveFilesUseCase
import dev.arkbuilders.drop.domain.usecase.SendFilesUseCase
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
import org.koin.dsl.module

val useCaseModule =
    module {
        factory<SendFilesUseCase> { SendFilesUseCase(get(), get(), get<FirebaseReporter>()) }
        factory<ReceiveFilesUseCase> { ReceiveFilesUseCase(get(), get<FirebaseReporter>()) }
    }
