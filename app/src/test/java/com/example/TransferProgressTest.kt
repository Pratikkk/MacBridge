package com.example

import com.example.model.*
import com.example.ui.screens.files.transferBytes
import com.example.ui.screens.files.transferPercent
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun `small files and invalid progress remain readable and bounded`() {
        assertEquals("0 B", transferBytes(0))
        assertEquals("1 B", transferBytes(1))
        assertEquals("0 B", transferBytes(-1))
        val item = FileTransferItem("file", "🌉.txt", 80_000, 65_536, TransferDirection.INCOMING, TransferStatus.TRANSFERRING, "hash")
        assertEquals(81, transferPercent(item))
        assertEquals(100, transferPercent(item.copy(transferredBytes = Long.MAX_VALUE)))
        assertEquals(0, transferPercent(item.copy(transferredBytes = -1)))
        assertEquals(0, transferPercent(item.copy(fileSize = 0)))
    }
}
