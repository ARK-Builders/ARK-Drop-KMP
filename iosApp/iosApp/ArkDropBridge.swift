import Foundation
import ArkDrop

// MARK: - Base32 Decoding (RFC 4648)

private let base32Alphabet: [Character: UInt8] = {
    let chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var dict: [Character: UInt8] = [:]
    for (i, c) in chars.enumerated() {
        dict[c] = UInt8(i)
    }
    return dict
}()

private func decodeBase32(_ encoded: String) -> Data? {
    let uppercased = encoded.uppercased()
    var bits: UInt64 = 0
    var bitCount = 0
    var result = Data()
    
    for char in uppercased {
        guard let value = base32Alphabet[char] else { continue }
        bits = (bits << 5) | UInt64(value)
        bitCount += 5
        if bitCount >= 8 {
            bitCount -= 8
            result.append(UInt8((bits >> bitCount) & 0xFF))
            bits &= (1 << bitCount) - 1
        }
    }
    return result
}

// MARK: - Ticket Diagnostic

private func diagnoseTicket(_ ticket: String) {
    guard ticket.hasPrefix("node") else {
        print("[TicketDiag] Ticket does not start with 'node' prefix")
        return
    }
    let encoded = String(ticket.dropFirst(4))
    guard let data = decodeBase32(encoded) else {
        print("[TicketDiag] Failed to decode base32 ticket data")
        return
    }
    print("[TicketDiag] Decoded ticket: \(data.count) bytes")
    
    // iroh node ticket (v2) CBOR structure:
    // [
    //   node_id: [UInt8; 32],   // Ed25519 public key
    //   relay_url: string,       // DERP relay URL
    //   alpn: [UInt8]?,          // optional ALPN
    // ]
    // In CBOR, this is typically encoded as:
    // 0x82 = array(2) or 0x83 = array(3)
    // followed by bytes(32) for node_id, text(string) for relay_url, optional bytes for alpn
    
    guard data.count >= 34 else {
        print("[TicketDiag] Ticket data too short (\(data.count) bytes), cannot decode")
        print("[TicketDiag] Raw hex: \(data.map { String(format: "%02x", $0) }.joined())")
        return
    }
    
    // Skip CBOR array header (1 byte), then node_id is 32 bytes of byte string
    var offset = 0
    
    // Parse the CBOR array
    guard offset < data.count else { return }
    let first = data[offset]
    offset += 1
    
    // Array header: 0x80 | 0x1F for array of length 1-31, or 0x98 followed by length byte
    var arrayLength = 0
    if first >= 0x80 && first <= 0x9F {
        if first <= 0x97 {
            arrayLength = Int(first & 0x1F)
        } else if first == 0x98 && offset < data.count {
            arrayLength = Int(data[offset])
            offset += 1
        }
    }
    print("[TicketDiag] CBOR array length: \(arrayLength)")
    
    // Parse element 0: node_id (byte string)
    if offset < data.count {
        let bsHeader = data[offset]
        offset += 1
        var byteStringLen = 0
        if bsHeader >= 0x40 && bsHeader <= 0x57 {
            byteStringLen = Int(bsHeader & 0x1F)
        } else if bsHeader == 0x58 && offset < data.count {
            byteStringLen = Int(data[offset])
            offset += 1
        } else if bsHeader == 0x59 && offset + 1 < data.count {
            byteStringLen = (Int(data[offset]) << 8) | Int(data[offset + 1])
            offset += 2
        }
        
        if byteStringLen == 32 && offset + 32 <= data.count {
            let nodeId = data[offset..<offset + 32]
            offset += 32
            print("[TicketDiag] Node ID: \(nodeId.map { String(format: "%02x", $0) }.joined())")
        } else {
            print("[TicketDiag] Unexpected byte string length: \(byteStringLen) at offset \(offset-1)")
            print("[TicketDiag] Raw hex: \(data.map { String(format: "%02x", $0) }.joined())")
        }
    }
    
    // Parse element 1: relay_url (text string)
    if offset < data.count {
        let tsHeader = data[offset]
        offset += 1
        var textLen = 0
        if tsHeader >= 0x60 && tsHeader <= 0x77 {
            textLen = Int(tsHeader & 0x1F)
        } else if tsHeader == 0x78 && offset < data.count {
            textLen = Int(data[offset])
            offset += 1
        } else if tsHeader == 0x79 && offset + 1 < data.count {
            textLen = (Int(data[offset]) << 8) | Int(data[offset + 1])
            offset += 2
        }
        
        if offset + textLen <= data.count {
            let relayData = data[offset..<offset + textLen]
            offset += textLen
            if let relayURL = String(data: relayData, encoding: .utf8) {
                print("[TicketDiag] Relay URL: '\(relayURL)'")
            } else {
                print("[TicketDiag] Relay URL (raw): \(relayData.map { String(format: "%02x", $0) }.joined())")
            }
        }
    }
    
    // Parse element 2: alpn (optional byte string)
    if arrayLength > 2 && offset < data.count {
        let alpnHeader = data[offset]
        offset += 1
        var alpnLen = 0
        if alpnHeader >= 0x40 && alpnHeader <= 0x57 {
            alpnLen = Int(alpnHeader & 0x1F)
        } else if alpnHeader == 0x58 && offset < data.count {
            alpnLen = Int(data[offset])
            offset += 1
        }
        if offset + alpnLen <= data.count {
            let alpnData = data[offset..<offset + alpnLen]
            print("[TicketDiag] ALPN: \(alpnData.map { String(format: "%02x", $0) }.joined())")
        }
    }
}

// Note: Objective-C types from ArkDropBridge.h are made available to Swift
// through the bridging header (ARK_Drop-Bridging-Header.h)
// This file should import "ArkDropBridge.h" to expose the Objective-C types

// MARK: - Direct Diagnostic (bypasses ObjC bridge entirely)

private final class DataSenderFileData: SenderFileData, @unchecked Sendable {
    private let data: Data
    private var offset: Int = 0

    init(data: Data) {
        self.data = data
    }

    func len() -> UInt64 { UInt64(data.count) }

    func read() -> UInt8? {
        guard offset < data.count else { return nil }
        let byte = data[offset]
        offset += 1
        return byte
    }

    func readChunk(size: Int32) -> Data {
        let chunkSize = min(Int(size), data.count - offset)
        guard chunkSize > 0 else { return Data() }
        let chunk = data[offset..<offset + chunkSize]
        offset += chunkSize
        return chunk
    }
}

/// Diagnostic: call ArkDrop.sendFiles directly from Swift, bypassing all bridges.
/// This runs asynchronously and does NOT block the normal send flow.
/// It's a fire-and-forget side-by-side comparison for diagnostic purposes.
private func diagnosticDirectSend(request: ArkDropSendFilesRequest) {
    print("[Diagnostic] ============ DIRECT SEND TEST ============")
    let testData = "ARKDrop diagnostic test file content".data(using: .utf8)!
    let fileData = DataSenderFileData(data: testData)

    let profile = SenderProfile(name: request.profile.name, avatarB64: request.profile.avatarB64)
    let config = request.config.map { c in
        SenderConfig(chunkSize: c.chunkSize, parallelStreams: c.parallelStreams)
    }
    let swiftRequest = SendFilesRequest(
        profile: profile,
        files: [SenderFile(name: "diagnostic.txt", data: fileData)],
        config: config
    )

    Task {
        do {
            print("[Diagnostic] Calling ArkDrop.sendFiles (DIRECT, in-memory data)...")
            let bubble = try await ArkDrop.sendFiles(request: swiftRequest)
            let ticket = bubble.getTicket()
            print("[Diagnostic] Direct sendFiles succeeded, ticket=\(ticket), conf=\(bubble.getConfirmation())")
            diagnoseTicket(ticket)
            print("[Diagnostic] Starting 30s monitoring of direct bubble...")
            for i in 0..<60 {
                try await Task.sleep(nanoseconds: 500_000_000)
                let connected = bubble.isConnected()
                let finished = bubble.isFinished()
                print("[Diagnostic] Direct bubble check #\(i+1): isConnected=\(connected), isFinished=\(finished)")
                if connected || finished { break }
            }
            print("[Diagnostic] ============ DIRECT SEND TEST END ============")
        } catch {
            print("[Diagnostic] Direct sendFiles failed: \(error)")
        }
    }
}

// MARK: - Objective-C Bridge Implementation

// Bridge class for Objective-C interop
@objc(ArkDropBridgeSwift) public class ArkDropBridgeSwift: NSObject {
    /// Non-blocking sendFiles - calls completion on a background thread when done.
    /// No thread is blocked during the async operation.
    @objc public static func sendFiles(withRequest request: ArkDropSendFilesRequest,
                                        completion: @escaping (ArkDropSendFilesBubble?, NSError?) -> Void) {
        print("[ArkDropBridge] sendFiles (callback) called, files count: \(request.files.count)")
        
        // Run a direct diagnostic send in parallel (in-memory data, no bridge)
        diagnosticDirectSend(request: request)
        
        Task {
            do {
                let swiftRequest = convertToSwiftSendRequest(request)
                print("[ArkDropBridge] Calling ArkDrop.sendFiles...")
                let swiftBubble = try await ArkDrop.sendFiles(request: swiftRequest)
                let ticket = swiftBubble.getTicket()
                print("[ArkDropBridge] ArkDrop.sendFiles succeeded, ticket=\(ticket), conf=\(swiftBubble.getConfirmation())")
                diagnoseTicket(ticket)
                let bubbleImpl = ArkDropSendFilesBubbleImpl(bubble: swiftBubble)
                completion(bubbleImpl, nil)
            } catch let err as NSError {
                print("[ArkDropBridge] ArkDrop.sendFiles failed: \(err.localizedDescription)")
                completion(nil, err)
            } catch {
                print("[ArkDropBridge] ArkDrop.sendFiles failed: \(String(describing: error))")
                completion(nil, NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)]))
            }
        }
    }

    /// Blocking sendFiles - kept for backward compatibility
    @objc(sendFilesBlockingWithRequest:bubble:error:) public static func sendFiles(
        withRequest request: ArkDropSendFilesRequest,
        bubble: AutoreleasingUnsafeMutablePointer<ArkDropSendFilesBubble?>,
        error: NSErrorPointer
    ) {
        print("[ArkDropBridge] sendFiles (blocking) called, files count: \(request.files.count)")
        let semaphore = DispatchSemaphore(value: 0)
        var resultBubble: ArkDropSendFilesBubble?
        var resultError: NSError?
        
        Task {
            do {
                let swiftRequest = convertToSwiftSendRequest(request)
                print("[ArkDropBridge] Calling ArkDrop.sendFiles...")
                let swiftBubble = try await ArkDrop.sendFiles(request: swiftRequest)
                let ticket = swiftBubble.getTicket()
                print("[ArkDropBridge] ArkDrop.sendFiles succeeded, ticket=\(ticket), conf=\(swiftBubble.getConfirmation())")
                // Log ticket analysis
                if ticket.hasPrefix("node") {
                    print("[ArkDropBridge] Ticket has 'node' prefix, raw length: \(ticket.count)")
                    print("[ArkDropBridge] Full ticket: \(ticket)")
                }
                resultBubble = ArkDropSendFilesBubbleImpl(bubble: swiftBubble)
            } catch let err as NSError {
                print("[ArkDropBridge] ArkDrop.sendFiles failed: \(err.localizedDescription)")
                resultError = err
            } catch {
                print("[ArkDropBridge] ArkDrop.sendFiles failed: \(String(describing: error))")
                resultError = NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)])
            }
            semaphore.signal()
        }
        
        semaphore.wait()
        
        if let err = resultError {
            error?.pointee = err
        } else {
            bubble.pointee = resultBubble
            print("[ArkDropBridge] sendFiles returning bubble to caller")
        }
    }
    
    @objc public static func receiveFiles(withRequest request: ArkDropReceiveFilesRequest,
                                           bubble: AutoreleasingUnsafeMutablePointer<ArkDropReceiveFilesBubble?>,
                                           error: NSErrorPointer) {
        print("[ArkDropBridge] receiveFiles (blocking) called, ticket prefix: \(request.ticket.prefix(50)), conf: \(request.confirmation), profile: \(request.profile.name)")
        let semaphore = DispatchSemaphore(value: 0)
        var resultBubble: ArkDropReceiveFilesBubble?
        var resultError: NSError?
        
        Task {
            do {
                let swiftRequest = convertToSwiftReceiveRequest(request)
                print("[ArkDropBridge] Calling ArkDrop.receiveFiles...")
                let swiftBubble = try await ArkDrop.receiveFiles(request: swiftRequest)
                print("[ArkDropBridge] ArkDrop.receiveFiles succeeded")
                resultBubble = ArkDropReceiveFilesBubbleImpl(bubble: swiftBubble)
            } catch let err as NSError {
                print("[ArkDropBridge] ArkDrop.receiveFiles failed: \(err.localizedDescription)")
                resultError = err
            } catch {
                print("[ArkDropBridge] ArkDrop.receiveFiles failed: \(String(describing: error))")
                resultError = NSError(domain: "ArkDropBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: String(describing: error)])
            }
            semaphore.signal()
        }
        
        // Wait for async operation to complete
        semaphore.wait()
        print("[ArkDropBridge] receiveFiles returning, success=\(resultError == nil)")
        
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
        print("[ArkDropBridge] ArkDropSendFilesBubbleImpl created, ticket=\(bubble.getTicket())")
    }

    deinit {
        print("[ArkDropBridge] ArkDropSendFilesBubbleImpl DEINIT, isFinished=\(bubble.isFinished()), isConnected=\(bubble.isConnected())")
    }
    
    @objc(getTicket) public func getTicket() -> String {
        bubble.getTicket()
    }
    
    @objc(getConfirmation) public func getConfirmation() -> UInt8 {
        bubble.getConfirmation()
    }
    
    @objc(cancelWithCompletion:) public func cancel(completion: @escaping ((any Error)?) -> Void) {
        print("[ArkDropBridge] cancel called")
        Task {
            print("[ArkDropBridge] cancel - inside Task, isFinished=\(bubble.isFinished()), isConnected=\(bubble.isConnected())")
            do {
                try await bubble.cancel()
                print("[ArkDropBridge] cancel completed")
                completion(nil)
            } catch {
                print("[ArkDropBridge] cancel failed: \(error.localizedDescription)")
                completion(error)
            }
        }
    }
    
    @objc(isFinished) public func isFinished() -> Bool {
        let isFinished = bubble.isFinished()
        print("[ArkDropBridge] ArkDropSendFilesBubbleImpl isFinished=\(isFinished)")
        return isFinished
    }
    
    @objc(isConnected) public func isConnected() -> Bool {
        let isConnected = bubble.isConnected()
        print("[ArkDropBridge] ArkDropSendFilesBubbleImpl isConnected=\(isConnected)")
        return isConnected
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
        print("[ArkDropBridge-Sub] \(message)")
        subscriber.log(withMessage: message)
    }
    
    func notifySending(event: SendFilesSendingEvent) {
        print("[ArkDropBridge-Sub] notifySending: name=\(event.name), sent=\(event.sent), remaining=\(event.remaining)")
        subscriber.notifySending(withName: event.name,
                                 sent: event.sent,
                                 remaining: event.remaining)
    }
    
    func notifyConnecting(event: SendFilesConnectingEvent) {
        print("[ArkDropBridge-Sub] notifyConnecting: receiver=\(event.receiver.name)")
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
