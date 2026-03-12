/*
 * Copyright (C) 2024 Aigentik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aigentik.app.email

import android.content.Context
import android.accounts.Account
import android.accounts.AccountManager
import android.net.Uri
import androidx.room.*
import com.aigentik.app.data.AppDB
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailAccountDao {
    @Query("SELECT * FROM email_accounts ORDER BY displayName")
    fun getAllAccounts(): Flow<List<EmailAccount>>
    
    @Query("SELECT * FROM email_accounts WHERE id = :id")
    suspend fun getAccountById(id: Long): EmailAccount?
    
    @Query("SELECT * FROM email_accounts WHERE isActive = 1")
    fun getActiveAccounts(): Flow<List<EmailAccount>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: EmailAccount): Long
    
    @Update
    suspend fun updateAccount(account: EmailAccount)
    
    @Delete
    suspend fun deleteAccount(account: EmailAccount)
    
    @Query("UPDATE email_accounts SET isActive = :isActive WHERE id = :id")
    suspend fun setAccountActive(id: Long, isActive: Boolean)
}

@Dao
interface EmailMessageDao {
    @Query("SELECT * FROM email_messages WHERE accountId = :accountId ORDER BY receivedDate DESC")
    fun getMessagesByAccount(accountId: Long): Flow<List<EmailMessage>>
    
    @Query("SELECT * FROM email_messages WHERE id = :id")
    suspend fun getMessageById(id: Long): EmailMessage?
    
    @Query("SELECT * FROM email_messages WHERE accountId = :accountId AND isRead = 0 ORDER BY receivedDate DESC")
    fun getUnreadMessages(accountId: Long): Flow<List<EmailMessage>>
    
    @Query("SELECT * FROM email_messages WHERE accountId = :accountId AND subject LIKE :query OR from LIKE :query ORDER BY receivedDate DESC")
    fun searchMessages(accountId: Long, query: String): Flow<List<EmailMessage>>
    
    @Query("SELECT * FROM email_messages WHERE threadId = :threadId ORDER BY receivedDate ASC")
    fun getThreadMessages(threadId: String): Flow<List<EmailMessage>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: EmailMessage): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<EmailMessage>)
    
    @Update
    suspend fun updateMessage(message: EmailMessage)
    
    @Delete
    suspend fun deleteMessage(message: EmailMessage)
    
    @Query("UPDATE email_messages SET isRead = :isRead WHERE id = :id")
    suspend fun setMessageRead(id: Long, isRead: Boolean)
    
    @Query("UPDATE email_messages SET isStarred = :isStarred WHERE id = :id")
    suspend fun setMessageStarred(id: Long, isStarred: Boolean)
}

@Entity(tableName = "email_accounts")
data class EmailAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val displayName: String,
    val provider: String,
    val isActive: Boolean = true
)

@Entity(tableName = "email_messages")
data class EmailMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val subject: String,
    val from: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val body: String,
    val bodyHtml: String?,
    val receivedDate: Long,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val labels: String,
    val threadId: String?,
    val messageId: String?,
    val isGVoice: Boolean = false
)

@TypeConverters
class EmailTypeConverters {
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString("|")
    
    @TypeConverter
    fun toStringList(string: String): List<String> = if (string.isEmpty()) emptyList() else string.split("|")
}
