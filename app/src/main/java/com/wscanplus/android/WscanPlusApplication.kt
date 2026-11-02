package com.wscanplus.android

import android.app.Application
import com.wscanplus.android.core.AgentMcpServer
import com.wscanplus.android.data.db.AppDatabase

class WscanPlusApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val mcpServer: AgentMcpServer by lazy { AgentMcpServer(this) }
}
