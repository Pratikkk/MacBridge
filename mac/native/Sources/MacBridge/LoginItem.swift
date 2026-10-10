import Foundation
import Combine
import ServiceManagement

protocol LoginItemService {
    var status: SMAppService.Status { get }
    func register() throws
    func unregister() throws
}

extension SMAppService: LoginItemService {}

/// Read the OS state on activation; no polling or separate persisted enabled flag.
@MainActor
final class LoginItemController: ObservableObject {
    static let shared = LoginItemController(service: SMAppService.mainApp)
    @Published private(set) var status: SMAppService.Status = .notRegistered
    @Published private(set) var errorMessage: String?
    private let service: LoginItemService

    init(service: LoginItemService) {
        self.service = service
        refresh()
    }

    var requested: Bool { status == .enabled || status == .requiresApproval }
    var requiresApproval: Bool { status == .requiresApproval }

    func refresh() {
        let actual = service.status
        if actual != status { status = actual }
    }

    func setEnabled(_ enabled: Bool) {
        errorMessage = nil
        do {
            if enabled { try service.register() }
            else { try service.unregister() }
        } catch {
            errorMessage = "Could not change Open at Login. Check Login Items in System Settings and try again."
        }
        refresh()
    }
}
