package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamvault.data.local.entity.NasTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NasTransferDao {
    @Query("SELECT * FROM nas_transfers ORDER BY created_at ASC")
    fun observeAll(): Flow<List<NasTransferEntity>>

    @Query("SELECT * FROM nas_transfers WHERE id = :id")
    suspend fun getById(id: String): NasTransferEntity?

    /** The future service uses this to recover rows whose temporary .part file must be inspected. */
    @Query("SELECT * FROM nas_transfers WHERE status IN ('PENDING', 'IN_PROGRESS', 'INTERRUPTED') ORDER BY created_at ASC")
    suspend fun getRecoverableQueue(): List<NasTransferEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: NasTransferEntity)

    @Update
    suspend fun update(transfer: NasTransferEntity)
}
