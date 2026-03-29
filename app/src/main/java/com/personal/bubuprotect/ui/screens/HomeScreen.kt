package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight.Companion.SemiBold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.components.Font01
import com.personal.bubuprotect.ui.components.Font02
import com.personal.bubuprotect.ui.components.Primary01
import com.personal.bubuprotect.ui.components.Primary02
import com.personal.bubuprotect.ui.components.createImageLoader

@Composable
fun HomeScreen(
    isPasswordVisible: Boolean,
    onVisibilityChange: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val imageLoader = remember { createImageLoader(context) }
    Box(
        modifier = Modifier
            .height(300.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Primary01)
        ) {
            //HomeScreen Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(Primary01)
                    .padding(8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(R.drawable.suspicious, imageLoader),
                        contentDescription = "Welcome Image",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(BorderStroke(2.dp, Primary02), shape = CircleShape)
                            .size(60.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Bubu Protect",
                        color = Font01,
                        style = TextStyle(
                            fontSize = 24.sp,
                            lineHeight = 24.sp,
                            letterSpacing = 0.5.sp,
                            fontWeight = SemiBold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onVisibilityChange() }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(if (isPasswordVisible) R.drawable.icon_show_password else R.drawable.icon_hide_password),
                            contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                            tint = Font02
                        )
                    }

                }
            }
            if (isPasswordVisible) {
                var flipped by remember { mutableStateOf(false) }

                // Animate rotation between 0f and 180f
                val rotation by animateFloatAsState(
                    targetValue = if (flipped) 180f else 0f,
                    animationSpec = tween(durationMillis = 1000),
                    label = "rotation"
                )

                Column(Modifier.fillMaxSize()) {
                    val cardShape: Shape = RoundedCornerShape(16.dp)

                    Card(
                        modifier = Modifier
                            .clip(cardShape)
                            .fillMaxWidth()
                            .height(250.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(
                                interactionSource = interactionSource,
                                onClick = { flipped = !flipped }
                            )

                            .graphicsLayer {
                                rotationY = rotation
                                cameraDistance = 12f * density
                            },
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 5.dp,
                            pressedElevation = 20.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = Primary01
                        ),

                        ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Primary01),
                            contentAlignment = Alignment.Center
                        ) {
                            if (rotation <= 90f) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.hide,
                                        imageLoader
                                    ),
                                    contentDescription = "Welcome Image",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .border(BorderStroke(2.dp, Primary02), shape = CircleShape)
                                        .size(150.dp)

                                )
                            } else {

                                Text(
                                    "Sensitive Side",
                                    modifier = Modifier.graphicsLayer {
                                        rotationY = 180f
                                    }
                                )
                            }
                        }
                    }
                    LazyColumn() {


                    }
                }
            }
        }
        Image(
            painter = rememberAsyncImagePainter(R.drawable.welcome, imageLoader),
            contentDescription = "Welcome Image",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .height(200.dp)
                .width(150.dp)
                .align(Alignment.BottomStart)
        )
    }
}