package com.personal.bubuprotect.di

import com.personal.bubuprotect.data.remote.LuxandApiService
import com.personal.bubuprotect.data.repository.FaceRecognitionRepositoryImpl
import com.personal.bubuprotect.domain.repository.FaceRecognitionRepository
import com.personal.bubuprotect.services.BiometricHelper
import com.personal.bubuprotect.ui.camera.FaceRecognitionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module


val appModule = module {
    // Services
    singleOf(::BiometricHelper)
    single { LuxandApiService.create() }

    // Repositories
    single { FaceRecognitionRepositoryImpl(get(), androidContext()) } bind FaceRecognitionRepository::class
    
    // ViewModels
    viewModelOf(::FaceRecognitionViewModel)
}
