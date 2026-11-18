package com.novelreader.ui.screens.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class OnboardPage(
    val title: String,
    val body: String,
    val accent: Color,
)

private val pages = listOf(
    OnboardPage(
        title = "一站式阅读",
        body = "小说、漫画、影视与订阅，在同一个应用里搜索、收藏、继续阅读。",
        accent = Color(0xFF5B8DEF),
    ),
    OnboardPage(
        title = "书源由你掌控",
        body = "导入书源，跨源搜索与换源切换；从发现页按分类逛，找书不再受限。",
        accent = Color(0xFF37B58A),
    ),
    OnboardPage(
        title = "沉浸式阅读",
        body = "可调排版、夜间模式、手势与自动翻页，把阅读调成你喜欢的样子。",
        accent = Color(0xFFC77BE0),
    ),
)

/**
 * 新用户引导页（C16）：首启展示一次，3 页介绍核心能力，末页「开始使用」写首启标记后进首页。
 * 用自包含实现（不引入新依赖）：AnimatedContent 切页 + 圆点指示器。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val current = pages[page]
    val isLast = page == pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 跳过
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("跳过") }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 装饰图形：渐变圆，不依赖图标库
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(current.accent, current.accent.copy(alpha = 0.35f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${page + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                )
            }

            Spacer(Modifier.height(40.dp))

            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding-text",
            ) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pages[p].title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = pages[p].body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // 圆点指示器
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { i ->
                val selected = i == page
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        ),
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page > 0) {
                TextButton(onClick = { page-- }) { Text("上一步") }
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = { if (isLast) onDone() else page++ }) {
                Text(if (isLast) "开始使用" else "下一步")
            }
        }
    }
}
