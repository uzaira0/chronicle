package com.openlattice.chronicle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.StorageQueue
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class ChronicleDbTests {

    private lateinit var isolatedDb: IsolatedChronicleTestDb
    private lateinit var chronicleDb: ChronicleDb
    private lateinit var storageQueue: StorageQueue

    @Before
    fun setupChronicleDb() {
        isolatedDb = IsolatedChronicleTestDb.create("chronicle_db")
        chronicleDb = isolatedDb.db
        storageQueue = chronicleDb.queueEntryData()
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    @Test
    fun testChronicleReadWriteSingleQueueEntry() {
        val qe = QueueEntry(System.currentTimeMillis(), 1, ByteArray(8, { i -> (i * i).toByte() }))
        storageQueue.insertEntry(qe);
        val actual = storageQueue.getNextEntries(1)[0]
        Assert.assertEquals(qe, actual)

        storageQueue.deleteEntry(qe)

        val qe1 = QueueEntry(System.currentTimeMillis(), 1, ByteArray(8, { i -> (i * i).toByte() }))
        Thread.sleep(100);
        val qe2 = QueueEntry(System.currentTimeMillis(), 1, ByteArray(8, { i -> (i * i).toByte() }))
        Thread.sleep(100);
        val qe3 = QueueEntry(System.currentTimeMillis(), 1, ByteArray(8, { i -> (i * i).toByte() }))
        Thread.sleep(100);
        val qe4 = QueueEntry(System.currentTimeMillis(), 1, ByteArray(8, { i -> (i * i).toByte() }))

        val qeList = ArrayList<QueueEntry>(4)
        qeList.add(qe4)
        qeList.add(qe2)
        qeList.add(qe1)
        qeList.add(qe3)

        storageQueue.insertEntries(qeList);
        val actualArr = storageQueue.getNextEntries(4)
        Assert.assertEquals(4, actualArr.size)
        Assert.assertEquals(qe1, actualArr[0])
        Assert.assertEquals(qe2, actualArr[1])
        Assert.assertEquals(qe3, actualArr[2])
        Assert.assertEquals(qe4, actualArr[3])
    }
}
