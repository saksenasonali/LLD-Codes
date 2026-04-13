/*
+---------------------+
| UploadController    |
+---------------------+
| uploadFile()        |
| getStatus()         |
+----------+----------+
           |
           v
+----------------------+
| UploadService        |
+----------------------+
| startUpload()        |
| processSyncUpload()   |
| enqueueAsyncUpload()  |
+----------+-----------+
           |
           +----------------------+
           |                      |
           v                      v
+---------------------+   +----------------------+
| StorageProviderFactory|  | UploadJobRepository  |
+---------------------+   +----------------------+
| getProvider()       |   | save(), find(), update()|
+----------+----------+   +----------------------+
           |
           v
+---------------------------------------+
| StorageProvider (interface)           |
+---------------------------------------+
| upload()                              |
| multipartUpload()                     |
| getObjectUrl()                        |
| delete()                              |
+-----------+---------------------------+
            |
   +--------+---------+---------+
   v                  v         v
+-----------+   +-------------+ +-------------+
| S3Provider|   | GCSProvider | | AzureProvider|
+-----------+   +-------------+ +-------------+

+----------------------+
| ChunkingService      |
+----------------------+
| splitFile()          |
| calculateChecksum()  |
+----------------------+

+----------------------+
| NotificationService  |
+----------------------+
| notifySuccess()      |
| notifyFailure()      |
+----------------------+
    |            |
    v            v
Webhook        EventBus / Polling

+----------------------+
| AsyncUploadWorker    |
+----------------------+
| consumeJob()         |
| retryFailedChunks()   |
+----------------------+


Upload Flow - 
function uploadFile(request):
    validate(request)
    provider = providerFactory.getProvider(request.providerName)

    // Traceable record
    uploadJob = jobRepository.create(
        status = PENDING,
        request = request
    )

    if request.mode == SYNC and request.fileSize < SYNC_THRESHOLD:
        try:
            result = processSyncUpload(request, provider)
            jobRepository.update(uploadJob.id, SUCCESS, result)
            notificationService.notifySuccess(request, result)
            return result
        catch exception e:
            jobRepository.update(uploadJob.id, FAILED, e.message)
            notificationService.notifyFailure(request, e)
            throw e
    else:
        queue.enqueue(uploadJob.id)
        return { jobId: uploadJob.id, status: "PENDING" }

Sync upload -
function processSyncUpload(request, provider):
    // avoids loading the whole file into memory.
    stream = openStream(request.filePath)

    if request.fileSize <= SINGLE_PART_LIMIT:
        metadata = provider.upload(
            bucket = request.bucketName,
            key = request.objectKey,
            stream = stream,
            credential = request.credential
        )
        return metadata
    else:
        return multipartUpload(request, provider) 

MultipartUpload
function multipartUpload(request, provider):
    parts = chunkingService.splitFile(request.filePath, CHUNK_SIZE)

    uploadId = provider.initMultipartUpload(request.bucketName, request.objectKey)

    parallel for part in parts with maxConcurrency = MAX_PARALLEL_CHUNKS:
        retry up to MAX_RETRIES:
            try:
                checksum = chunkingService.calculateChecksum(part)
                provider.uploadPart(uploadId, part.number, part.stream, checksum)
                markPartSuccess(part.number)
                break
            catch transientException:
                if retry exhausted:
                    markPartFailed(part.number)
                    abort upload

    if any part failed:
        provider.abortMultipartUpload(uploadId)
        throw UploadFailedException

    metadata = provider.completeMultipartUpload(uploadId)
    return metadata

Async worker
function consumeJob(jobId):
    job = jobRepository.find(jobId)
    jobRepository.update(jobId, IN_PROGRESS)

    try:
        result = processSyncUpload(job.request, providerFactory.getProvider(job.request.providerName))
        jobRepository.update(jobId, SUCCESS, result)
        notificationService.publishCompletion(jobId, SUCCESS, result)
    catch exception e:
        jobRepository.update(jobId, FAILED, error = e.message)
        notificationService.publishCompletion(jobId, FAILED, e.message)
        
1. Start upload
POST /v1/uploads
Request
{
 "providerName": "AWS",
 "bucketName": "my-bucket",
 "objectKey": "docs/report.pdf",
 "mode": "ASYNC",
 "filePath": "/tmp/report.pdf",
 "authDetails": {
   "authType": "TOKEN",
   "token": "encrypted-or-referenced-token"
 },
 // A callback URL is where we send the result after an async operation completes.
 "callbackUrl": "https://client.app/webhook/upload",
 "metadata": {
   "contentType": "application/pdf"
 },
 "idempotencyKey": "abc-123"
}
Response
{
 "jobId": "job_789",
 "status": "PENDING"
}

2. Get upload status
GET /v1/uploads/{jobId}
Response
{
 "jobId": "job_789",
 "status": "IN_PROGRESS",
 "progressPercent": 60,
 "providerName": "AWS",
 "objectKey": "docs/report.pdf"
}

3. Webhook callback payload
POST {callbackUrl}
{
 "jobId": "job_789",
 "status": "SUCCESS",
 "providerName": "AWS",
 "bucketName": "my-bucket",
 "objectKey": "docs/report.pdf",
 "etag": "xyz123"
}

4. Cancel upload
DELETE /v1/uploads/{jobId}
Useful for:
abort multipart uploads
stop queued jobs
release resources
*/
