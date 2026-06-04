package com.smartreimburse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.smartreimburse.ui.navigation.SmartReimburseNavHost
import com.smartreimburse.ui.theme.SmartReimburseTheme
import com.smartreimburse.viewmodel.SmartReimburseViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SmartReimburseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartReimburseTheme {
                SmartReimburseNavHost(viewModel = viewModel)
            }
        }
    }
}
