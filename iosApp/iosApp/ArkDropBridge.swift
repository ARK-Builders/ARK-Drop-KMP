import Foundation
import ArkDrop

// Note: Objective-C types from ArkDropBridge.h are made available to Swift
// through the bridging header (ARK_Drop-Bridging-Header.h)
// This file should import "ArkDropBridge.h" to expose the Objective-C types

// MARK: - Objective-C Bridge Implementation

// Bridge class for Objective-C interop
@objc(ArkDropBridgeSwift) public class ArkDropBridgeSwift: NSObject {
    /// Non-blocking sendFiles - calls completion on a background thread when done.
    /// No thread is blocked during the async operation.
    @objc public static func sendFiles(withRequest request: ArkDropSendFilesRequest,
                                        completion: @escaping (ArkDropSendFilesBubble?, NSError?) -> Void) {
        NSLog("[ArkDropBridge] sendFiles (callback) called, files count: %ld", request.files.count)
        
        Task {
            do {
                let swiftRequest = convertToSwiftSendRequest(request)
                NSLog("[ArkDropBridge] Calling ArkDrop.sendFiles...")
                let swiftBubble = try await ArkDrop.sendFiles(request: swiftRequest)
                let ticket = swiftBubble.getTicket()
                NSLog("[ArkDropBridge] ArkDrop.sendFiles succeeded, ticket=%@, conf=%hhu", ticket, swiftBubble.getConfirmation())
                let bubbleImpl = ArkDropSendFilesBubbleImpl(bubble: swiftBubble)
                completion(bubbleImpl, nil)
            } catch let err as NSError {
                NSLog("[ArkDropBridge] ArkDrop.sendFiles failed: %@", err.localizedDescription)
                completion(nil, err)
            } catch {
                NSLog("[ArkDropBridge] ArkDrop.sendFiles failed: %@", String(describing: error))
                completion(nil, NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)]))
            }
        }
    }

    /// Blocking sendFiles - kept for backward compatibility
    @objc public static func sendFiles(withRequest request: ArkDropSendFilesRequest,
                                        bubble: AutoreleasingUnsafeMutablePointer<ArkDropSendFilesBubble?>,
                                        error: NSErrorPointer) {
        NSLog("[ArkDropBridge] sendFiles (blocking) called, files count: %ld", request.files.count)
        let semaphore = DispatchSemaphore(value: 0)
        var resultBubble: ArkDropSendFilesBubble?
        var resultError: NSError?
        
        Task {
            do {
                let swiftRequest = convertToSwiftSendRequest(request)
                NSLog("[ArkDropBridge] Calling ArkDrop.sendFiles...")
                let swiftBubble = try await ArkDrop.sendFiles(request: swiftRequest)
                let ticket = swiftBubble.getTicket()
                NSLog("[ArkDropBridge] ArkDrop.sendFiles succeeded, ticket=%@, conf=%hhu", ticket, swiftBubble.getConfirmation())
                resultBubble = ArkDropSendFilesBubbleImpl(bubble: swiftBubble)
            } catch let err as NSError {
                NSLog("[ArkDropBridge] ArkDrop.sendFiles failed: %@", err.localizedDescription)
                resultError = err
            } catch {
                NSLog("[ArkDropBridge] ArkDrop.sendFiles failed: %@", String(describing: error))
                resultError = NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)])
            }
            semaphore.signal()
        }
        
        semaphore.wait()
        
        if let err = resultError {
            error?.pointee = err
        } else {
            bubble.pointee = resultBubble
            NSLog("[ArkDropBridge] sendFiles returning bubble to caller")
        }
    }
    
    @objc public static func receiveFiles(withRequest request: ArkDropReceiveFilesRequest,
                                          bubble: AutoreleasingUnsafeMutablePointer<ArkDropReceiveFilesBubble?>,
                                          error: NSErrorPointer) {
        let semaphore = DispatchSemaphore(value: 0)
        var resultBubble: ArkDropReceiveFilesBubble?
        var resultError: NSError?
        
        Task {
            do {
                let swiftRequest = convertToSwiftReceiveRequest(request)
                let swiftBubble = try await ArkDrop.receiveFiles(request: swiftRequest)
                resultBubble = ArkDropReceiveFilesBubbleImpl(bubble: swiftBubble)
            } catch let err as NSError {
                resultError = err
            } catch {
                resultError = NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)])
            }
            semaphore.signal()
        }
        
        // Wait for async operation to complete
        semaphore.wait()
        
        if let err = resultError {
            error?.pointee = err
        } else {
            bubble.pointee = resultBubble
        }
    }
}

// MARK: - Conversion Helpers

private func convertToSwiftSendRequest(_ request: ArkDropSendFilesRequest) -> SendFilesRequest {
    let profile = SenderProfile(
        name: request.profile.name,
        avatarB64: request.profile.avatarB64
    )
    
    let files = request.files.map { file in
        SenderFile(
            name: file.name,
            data: ArkDropSenderFileDataBridge(data: file.data)
        )
    }
    
    let config = request.config.map { c in
        SenderConfig(
            chunkSize: c.chunkSize,
            parallelStreams: c.parallelStreams
        )
    }
    
    return SendFilesRequest(
        profile: profile,
        files: files,
        config: config
    )
}

private func convertToSwiftReceiveRequest(_ request: ArkDropReceiveFilesRequest) -> ReceiveFilesRequest {
    let profile = ReceiverProfile(
        name: request.profile.name,
        avatarB64: request.profile.avatarB64
    )
    
    let config = ReceiverConfig(
        chunkSize: request.config.chunkSize,
        parallelStreams: request.config.parallelStreams
    )
    
    return ReceiveFilesRequest(
        ticket: request.ticket,
        confirmation: request.confirmation,
        profile: profile,
        config: config
    )
}

// MARK: - Bridge Implementations

@objc(ArkDropSendFilesBubbleImpl) public class ArkDropSendFilesBubbleImpl: NSObject, ArkDropSendFilesBubble {
    private let bubble: SendFilesBubble
    
    public init(bubble: SendFilesBubble) {
        self.bubble = bubble
        super.init()
    }
    
    @objc(getTicket) public func getTicket() -> String {
        bubble.getTicket()
    }
    
    @objc(getConfirmation) public func getConfirmation() -> UInt8 {
        bubble.getConfirmation()
    }
    
    @objc(cancelWithCompletion:) public func cancel(completion: @escaping ((any Error)?) -> Void) {
        NSLog("[ArkDropBridge] cancel called, isFinished=%{BOOL}d, isConnected=%{BOOL}d", bubble.isFinished(), bubble.isConnected())
        Task {
            do {
                try await bubble.cancel()
                NSLog("[ArkDropBridge] cancel completed")
                completion(nil)
            } catch {
                NSLog("[ArkDropBridge] cancel failed: %@", error.localizedDescription)
                completion(error)
            }
        }
    }
    
    @objc(isFinished) public func isFinished() -> Bool {
        bubble.isFinished()
    }
    
    @objc(isConnected) public func isConnected() -> Bool {
        bubble.isConnected()
    }
    
    @objc(getCreatedAt) public func getCreatedAt() -> String {
        bubble.getCreatedAt()
    }
    
    @objc(subscribeWithSubscriber:) public func subscribe(
        with subscriber: ArkDropSendFilesSubscriber
    ) {
        let swiftSubscriber = ArkDropSendFilesSubscriberBridge(subscriber: subscriber)
        bubble.subscribe(subscriber: swiftSubscriber)
    }
    
    @objc(unsubscribeWithSubscriber:) public func unsubscribe(
        with subscriber: ArkDropSendFilesSubscriber
    ) {
        let swiftSubscriber = ArkDropSendFilesSubscriberBridge(subscriber: subscriber)
        bubble.unsubscribe(subscriber: swiftSubscriber)
    }
}

@objc(ArkDropReceiveFilesBubbleImpl) public class ArkDropReceiveFilesBubbleImpl: NSObject, ArkDropReceiveFilesBubble {

    private let bubble: ReceiveFilesBubble
    
    public init(bubble: ReceiveFilesBubble) {
        self.bubble = bubble
        super.init()
    }
    
    @objc(startWithError:) public func startWithError(_ error: NSErrorPointer) {
        do {
            try bubble.start()
        } catch let err as NSError {
            error?.pointee = err
        } catch let caughtError {
            error?.pointee = NSError(
                domain: "ArkDropBridge",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: String(describing: caughtError)]
            )
        }
    }
    
    @objc public func cancel() {
        bubble.cancel()
    }
    
    @objc public func isFinished() -> Bool {
        bubble.isFinished()
    }
    
    @objc public func isCancelled() -> Bool {
        bubble.isCancelled()
    }
    
    @objc(subscribeWithSubscriber:) public func subscribe(
        with subscriber: ArkDropReceiveFilesSubscriber
    ) {
        let swiftSubscriber = ArkDropReceiveFilesSubscriberBridge(subscriber: subscriber)
        bubble.subscribe(subscriber: swiftSubscriber)
    }
    
    @objc(unsubscribeWithSubscriber:) public func unsubscribe(
        with subscriber: ArkDropReceiveFilesSubscriber
    ) {
        let swiftSubscriber = ArkDropReceiveFilesSubscriberBridge(subscriber: subscriber)
        bubble.unsubscribe(subscriber: swiftSubscriber)
    }
}

// MARK: - Adapter Classes

private final class ArkDropSenderFileDataBridge: SenderFileData, @unchecked Sendable {
    private let data: ArkDropSenderFileData
    
    init(data: ArkDropSenderFileData) {
        self.data = data
    }
    
    func len() -> UInt64 {
        data.len()
    }
    
    func isEmpty() -> Bool {
        // Default implementation if not available
        return data.len() == 0
    }
    
    func read() -> UInt8? {
        // data.read() returns NSNumber? from Objective-C, convert to UInt8?
        if let number = data.read() {
            return number.uint8Value
        }
        return nil
    }
    
    func readChunk(size: Int32) -> Data {
        // Bridge sync Objective-C method
        return data.readChunk(withSize: size)
    }
}

private final class ArkDropSendFilesSubscriberBridge: SendFilesSubscriber, @unchecked Sendable {
    private let subscriber: ArkDropSendFilesSubscriber
    
    init(subscriber: ArkDropSendFilesSubscriber) {
        self.subscriber = subscriber
    }
    
    func getId() -> String {
        subscriber.getId()
    }
    
    func log(message: String) {
        NSLog("[ArkDropBridge-Sub] %@", message)
        subscriber.log(withMessage: message)
    }
    
    func notifySending(event: SendFilesSendingEvent) {
        NSLog("[ArkDropBridge-Sub] notifySending: name=%@, sent=%llu, remaining=%llu", event.name, event.sent, event.remaining)
        subscriber.notifySending(withName: event.name,
                                 sent: event.sent,
                                 remaining: event.remaining)
    }
    
    func notifyConnecting(event: SendFilesConnectingEvent) {
        NSLog("[ArkDropBridge-Sub] notifyConnecting: receiver=%@", event.receiver.name)
        subscriber.notifyConnecting(withReceiverName: event.receiver.name,
                                    receiverAvatarB64: event.receiver.avatarB64)
    }
}

private final class ArkDropReceiveFilesSubscriberBridge: ReceiveFilesSubscriber, @unchecked Sendable {
    private let subscriber: ArkDropReceiveFilesSubscriber
    
    init(subscriber: ArkDropReceiveFilesSubscriber) {
        self.subscriber = subscriber
    }
    
    func getId() -> String {
        subscriber.getId()
    }
    
    func log(message: String) {
        subscriber.log(withMessage: message)
    }
    
    func notifyReceiving(event: ReceiveFilesReceivingEvent) {
        subscriber.notifyReceiving(withFileId: event.id, data: event.data)
    }
    
    func notifyConnecting(event: ReceiveFilesConnectingEvent) {
        let files = event.files.map { file in
            [
                "id": file.id,
                "name": file.name,
                "len": file.len
            ] as [String: Any]
        }
        subscriber.notifyConnecting(withSenderName: event.sender.name,
                                    senderAvatarB64: event.sender.avatarB64,
                                    files: files)
    }
}
