package com.personal.bubuprotect.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.components.Font01
import com.personal.bubuprotect.ui.components.Font02
import com.personal.bubuprotect.ui.components.Primary01
import com.personal.bubuprotect.ui.components.Primary02
import com.personal.bubuprotect.ui.components.colorBubu
import com.personal.bubuprotect.ui.components.createImageLoader

@Preview
@Composable
fun HomeScreen(
    isPasswordVisible: Boolean = true,
    onVisibilityChange: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val imageLoader = remember { createImageLoader(context) }
    // Toggle FLAG_SECURE for this screen only
    DisposableEffect(isPasswordVisible) {
        val window = (context as? Activity)?.window
        if(isPasswordVisible){
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize() // Changed from height(300.dp) to allow content to fit
            .background(Primary01),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            //HomeScreen Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
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

            // Move isPasswordVisible logic into AnimatedVisibility to allow exit animations
            AnimatedVisibility(
                visible = isPasswordVisible,
                enter = expandVertically(animationSpec = tween(800)) + fadeIn(),
                exit = shrinkOut(animationSpec = tween(800)) + fadeOut(),
            )
            {
                var flipped by remember { mutableStateOf(false) }

                // Animate rotation between 0f and 180f
                val rotation by animateFloatAsState(
                    targetValue = if (flipped) 180f else 0f,
                    animationSpec = tween(durationMillis = 1000),
                    label = "rotation"
                )

                Column(Modifier.fillMaxWidth()) {
                    val cardShape: Shape = RoundedCornerShape(16.dp)
                    Card(
                        modifier = Modifier
                            .clip(cardShape)
                            .fillMaxWidth()
                            .height(250.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null, // Optional: remove ripple to keep flip clean
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
                            containerColor = colorBubu
                        ),
                    )
                    {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(colorBubu),
                            contentAlignment = Alignment.Center
                        )
                        {
                            if (rotation <= 90f) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.hide,
                                        imageLoader
                                    ),
                                    contentDescription = "Welcome Image",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .size(150.dp)

                                )
                            } else {
                                Text(
                                    "Sensitive Side",
                                    color = Font01,
                                    modifier = Modifier.graphicsLayer {
                                        rotationY = 180f
                                    }
                                )
                            }
                        }
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        // Items will go here
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
