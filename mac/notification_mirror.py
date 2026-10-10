"""Session-only validated notification state; never persists content or executes actions."""
from collections import OrderedDict
import re


class NotificationMirror:
    def __init__(self):
        self.items = OrderedDict()
        self.pending = {}
        self.requested = {}

    def reset(self):
        self.items.clear()
        self.pending.clear()
        self.requested.clear()

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
            token = message.get('dismissToken')
            if token is not None and (not isinstance(token, str) or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', token)):
                raise ValueError('Invalid notification action handle')
            if token is not None:
                item['dismissToken'] = token
            if not isinstance(message.get('isDismissed', False), bool) or not isinstance(message.get('hasReplyAction', False), bool):
                raise ValueError('Invalid notification flags')
            if message.get('isDismissed'):
                return self.remove(key)
            if not enabled or self.items.get(key) == item:
                return []
            events = []
            if key not in self.items and len(self.items) >= 100:
                expired, _ = self.items.popitem(last=False)
                self.pending.pop(expired, None)
                self.requested.pop(expired, None)
                events.append(dict(operation='remove', notificationId=expired))
            self.pending.pop(key, None)
            if self.items.get(key, {}).get('dismissToken') != token:
                self.requested.pop(key, None)
            self.items[key] = item
            events.append(dict(operation='post', **item))
            return events
        if kind == 'NOTIFICATION_ACTION':
            action = message.get('actionType')
            if action == 'DISMISS_RESULT':
                key = self.field(message, 'notificationId', 512)
                token = self.field(message, 'actionToken', 36)
                status = message.get('status')
                if status not in ('REQUESTED', 'STALE', 'DENIED', 'UNAVAILABLE'):
                    raise ValueError('Invalid notification action result')
                if self.pending.get(key) != token or self.items.get(key, {}).get('dismissToken') != token:
                    return []
                self.pending.pop(key, None)
                return [dict(operation='result', notificationId=key, dismissToken=token, status=status)]
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

    def request_dismiss(self, key, token):
        item = self.items.get(key)
        if not isinstance(token, str) or not token or not item or item.get('dismissToken') != token or self.requested.get(key) == token:
            raise ValueError('This notification action is no longer available')
        self.pending[key] = token
        self.requested[key] = token
        return dict(type='NOTIFICATION_ACTION', notificationId=key, actionType='DISMISS', actionToken=token)

    def remove(self, key):
        self.pending.pop(key, None)
        self.requested.pop(key, None)
        if self.items.pop(key, None) is None:
            return []
        return [dict(operation='remove', notificationId=key)]
