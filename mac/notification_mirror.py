"""Session-only validated notification state; never persists content or executes actions."""
from collections import OrderedDict


class NotificationMirror:
    def __init__(self):
        self.items = OrderedDict()

    def reset(self):
        self.items.clear()

    @staticmethod
    def field(message, name, limit, blank=False):
        value = message.get(name)
        if not isinstance(value, str) or len(value.encode('utf-8')) > limit or (not blank and not value.strip()):
            raise ValueError('Invalid notification metadata')
        return value

    def process(self, message, enabled):
        kind = message.get('type')
        if kind == 'NOTIFICATION':
            key = self.field(message, 'notificationId', 512)
            package = self.field(message, 'packageName', 255)
            item = dict(notificationId=key, packageName=package,
                appName=self.field(message, 'appName', 400, True),
                title=self.field(message, 'title', 1024, True), text=self.field(message, 'text', 8192, True))
            if not isinstance(message.get('isDismissed', False), bool) or not isinstance(message.get('hasReplyAction', False), bool):
                raise ValueError('Invalid notification flags')
            if message.get('isDismissed'):
                return self.remove(key)
            if not enabled or self.items.get(key) == item:
                return []
            events = []
            if key not in self.items and len(self.items) >= 100:
                expired, _ = self.items.popitem(last=False)
                events.append(dict(operation='remove', notificationId=expired))
            self.items[key] = item
            events.append(dict(operation='post', **item))
            return events
        if kind == 'NOTIFICATION_ACTION':
            action = message.get('actionType')
            if action == 'REMOVE':
                return self.remove(self.field(message, 'notificationId', 512))
            if action == 'CLEAR':
                package = self.field(message, 'notificationId', 255, True)
                if not package:
                    self.reset()
                    return [dict(operation='clear')]
                result = []
                for key in list(self.items):
                    if self.items[key]['packageName'] == package:
                        result += self.remove(key)
                return result
            # Earlier clients may send DISMISS; it is never executed on either device.
            return []
        raise ValueError('Unsupported notification frame')

    def remove(self, key):
        if self.items.pop(key, None) is None:
            return []
        return [dict(operation='remove', notificationId=key)]
