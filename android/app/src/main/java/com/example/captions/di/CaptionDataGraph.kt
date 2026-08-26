package com.example.captions.di

import androidx.lifecycle.ViewModel
import com.example.captions.data.CaptionDataProvider
import com.example.captions.data.DefaultMockCaptionDataProvider
import com.example.captions.ui.CaptionEditorViewModel
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object CaptionDataModule {
    @Provides
    @Singleton
    fun provideCaptionDataProvider(): CaptionDataProvider = DefaultMockCaptionDataProvider()
}

@Singleton
@Component(modules = [CaptionDataModule::class])
interface CaptionDataComponent {
    fun captionEditorViewModel(): CaptionEditorViewModel
}

object CaptionDataGraph {
    val component: CaptionDataComponent by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DaggerCaptionDataComponent.create()
    }
}

inline fun <reified T : ViewModel> captionViewModel(): T =
    CaptionDataGraph.component.captionEditorViewModel() as T
