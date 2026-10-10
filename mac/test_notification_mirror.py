import unittest
from notification_mirror import NotificationMirror


def alert(key='key', **values):
    return dict(type='NOTIFICATION', notificationId=key, packageName='com.chat',
                appName='Chat 🌉', title='Hello 世界', text='Message', **values)


class NotificationMirrorTests(unittest.TestCase):
    def test_dismiss_handles_are_current_one_use_and_results_are_matched(self):
        mirror = NotificationMirror()
        token = '11111111-1111-1111-1111-111111111111'
        new = '22222222-2222-2222-2222-222222222222'
        mirror.process(alert(dismissToken=token), True)
        with self.assertRaises(ValueError): mirror.request_dismiss('key', 'wrong')
        request = mirror.request_dismiss('key', token)
        self.assertEqual('DISMISS', request['actionType'])
        with self.assertRaises(ValueError): mirror.request_dismiss('key', token)
        result = dict(type='NOTIFICATION_ACTION', actionType='DISMISS_RESULT', notificationId='key', actionToken=token, status='REQUESTED')
        self.assertEqual('result', mirror.process(result, True)[0]['operation'])
        self.assertEqual([], mirror.process(result, True))
        with self.assertRaises(ValueError): mirror.request_dismiss('key', token)
        mirror.process(alert(dismissToken=new), True)
        with self.assertRaises(ValueError): mirror.request_dismiss('key', token)
        mirror.request_dismiss('key', new)
        self.assertEqual([], mirror.process(result, True))
        mirror.remove('key')
        with self.assertRaises(ValueError): mirror.request_dismiss('key', new)
        mirror.process(alert(dismissToken=token), True); mirror.reset()
        with self.assertRaises(ValueError): mirror.request_dismiss('key', token)

    def test_dismiss_metadata_and_results_reject_malformed_input(self):
        for token in ('x', 123, 'x'*1000):
            with self.assertRaises(ValueError): NotificationMirror().process(alert(dismissToken=token), True)
        mirror = NotificationMirror(); mirror.process(alert(), True)
        with self.assertRaises(ValueError): mirror.request_dismiss('key', None)
        with self.assertRaises(ValueError):
            mirror.process(dict(type='NOTIFICATION_ACTION', actionType='DISMISS_RESULT', notificationId='key', actionToken='token', status='EXECUTED'), True)

    def test_opt_in_duplicates_updates_removal_and_unknown_keys(self):
        mirror = NotificationMirror()
        self.assertEqual([], mirror.process(alert(), False))
        self.assertEqual({}, mirror.items)
        self.assertEqual('post', mirror.process(alert(), True)[0]['operation'])
        self.assertEqual([], mirror.process(alert(timestamp=42), True))
        changed = alert(); changed['text'] = 'Updated 🌉'
        self.assertEqual('Updated 🌉', mirror.process(changed, True)[0]['text'])
        self.assertEqual([], mirror.process(dict(type='NOTIFICATION_ACTION', actionType='REMOVE', notificationId='unknown'), True))
        self.assertEqual('remove', mirror.process(dict(type='NOTIFICATION_ACTION', actionType='REMOVE', notificationId='key'), True)[0]['operation'])
        self.assertEqual([], mirror.process(dict(type='NOTIFICATION_ACTION', actionType='REMOVE', notificationId='key'), True))

    def test_flood_is_bounded_and_clear_filters_and_reset_remove_content(self):
        mirror = NotificationMirror()
        for i in range(1000):
            events = mirror.process(alert(str(i)), True)
        self.assertEqual(100, len(mirror.items))
        self.assertEqual(['remove', 'post'], [e['operation'] for e in events])
        other = alert('other'); other['packageName'] = 'com.other'
        mirror.process(other, True)
        mirror.process(dict(type='NOTIFICATION_ACTION', actionType='CLEAR', notificationId='com.chat'), False)
        self.assertEqual(['other'], list(mirror.items))
        self.assertEqual([dict(operation='clear')], mirror.process(dict(type='NOTIFICATION_ACTION', actionType='CLEAR', notificationId=''), False))
        self.assertFalse(mirror.items)
        mirror.process(alert(), True); mirror.reset(); self.assertFalse(mirror.items)

    def test_malformed_oversized_types_and_unsupported_actions_fail_safely(self):
        for field, value in [('notificationId','x'*513), ('packageName','x'*256), ('title','🌉'*257),
                             ('text','x'*8193), ('appName',None), ('isDismissed','false'), ('hasReplyAction',1)]:
            message = alert(); message[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                NotificationMirror().process(message, True)
        mirror = NotificationMirror(); mirror.process(alert(), True)
        for action in ('REPLY','DISMISS','EXECUTE'):
            self.assertEqual([], mirror.process(dict(type='NOTIFICATION_ACTION', notificationId='key', actionType=action), True))
        self.assertEqual(1, len(mirror.items))
        self.assertEqual('remove', mirror.process(alert(isDismissed=True), True)[0]['operation'])
