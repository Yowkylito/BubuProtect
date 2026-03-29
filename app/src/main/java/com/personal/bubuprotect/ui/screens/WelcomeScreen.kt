package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.components.Font01
import com.personal.bubuprotect.ui.components.Primary01

import com.personal.bubuprotect.ui.components.Primary02
import com.personal.bubuprotect.ui.components.createImageLoader



@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit = {}
) {

    val context = LocalContext.current

    val imageLoader = remember { createImageLoader(context) }
    val image by remember {
        mutableStateOf(
            listOf(
                R.drawable.protect,
                R.drawable.loading3,
            ).random()
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Primary01),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Box(
            modifier = Modifier
                .height(300.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberAsyncImagePainter(image, imageLoader),
                contentDescription = "Welcome Image",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSignIn() }
                    .border(BorderStroke(2.dp, Primary02), shape = CircleShape)
                    .size(300.dp)
                    .padding(horizontal = 4.dp)

            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text="Prove that you are my Bubu!",
            color= Font01,
            style = TextStyle(
                fontSize=24.sp,
                lineHeight=24.sp,
                letterSpacing=0.5.sp
            )
        )


    }
}