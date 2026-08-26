package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.data.repository.OpusRepository
import com.example.ui.screens.OpusProApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private lateinit var repository: OpusRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = OpusRepository(this)
        setContent {
            MyApplicationTheme {
                OpusProApp(repository)
            }
        }
    }
}
