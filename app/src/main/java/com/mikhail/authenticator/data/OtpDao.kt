package com.mikhail.authenticator.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OtpDao {

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<OtpEntry>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<OtpEntry>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): OtpEntry?

    @Insert
    suspend fun insert(entry: OtpEntry): Long

    @Insert
    suspend fun insertAll(entries: List<OtpEntry>)

    @Update
    suspend fun update(entry: OtpEntry)

    @Delete
    suspend fun delete(entry: OtpEntry)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    /** Used on import to skip accounts that are already present (same issuer+account). */
    @Query("SELECT COUNT(*) FROM accounts WHERE issuer = :issuer AND account = :account")
    suspend fun countByLabel(issuer: String, account: String): Int
}
