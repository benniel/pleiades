#!/usr/bin/python2
from pysage import *
from settings import *
from messages import *
from database import *
from network import *
from subprocess import *
from traceback import *
from socket import *
import time
import uuid
import os, os.path
import sys

template = '''<?xml version='1.0'?>
<!DOCTYPE simulator [
<!ATTLIST algorithm id ID #IMPLIED>
<!ATTLIST problem id ID #IMPLIED>
<!ATTLIST measurements id ID #IMPLIED>
]>
<simulator>
<algorithms>%s</algorithms>
<problems>%s</problems>
%s
<simulations>%s</simulations>
</simulator>'''

class Worker(Actor):
    subscriptions = ['JobMessage', 'NoJobMessage', 'AckResultMessage', 'KillMessage', 'RmJarMessage']

    def __init__(self):
        self.mgr = ActorManager.get_singleton()
        self.mgr.register_actor(self)
        self.mgr.connect(transport.SelectTCPTransport, host=SERVER_IP, port=SERVER_PORT)
        self.mgr.broadcast_message(JobRequestMessage(msg={'allowed_users':ALLOWED_USERS}))

        self.db, self.connection = mongo_connect(MONGO_RO_USER, MONGO_RO_PWD)
        self.status = ''
        self.running = True

    def get_status_string(self):
        return self.status

    def handle_JobMessage(self, msg):
        try:
            self.status = 'job received'
            sim = msg.get_property('msg')

            user_id = sim['user_id']
            sim_id = sim['sim_id']
            job_id = sim['job_id']
            current_sample = sim['sample']
            print "Executing: ", user_id, job_id, sim_id, current_sample

            self.status = 'getting xml'

            xml_name = '%s_%i_%i_%i.xml' % (user_id, job_id, sim_id, current_sample)
            meas_xml = self.db.xml.find_one({
                'type': 'meas',
                'idref': sim['meas'],
                'job_id': job_id,
                'user_id': user_id
            })
            prob_xml = self.db.xml.find_one({
                'type': 'prob',
                'idref': sim['prob'],
                'job_id': job_id,
                'user_id': user_id
            })
            algs_xml = list(self.db.xml.find({
                'type':'alg',
                'job_id':job_id,
                'user_id':user_id
            }))
            sim_xml = self.db.xml.find_one({
                'type':'sim',
                'job_id':job_id,
                'sim_id':sim_id,
                'user_id':user_id
            })

            self.status = 'writing files'

            output_file_name = str(uuid.uuid4())
            with open(xml_name, 'w') as xml_file:
                xml_string = template % (
                    '\n'.join([a['value'] for a in algs_xml]),
                    prob_xml['value'],
                    meas_xml['value'],
                    sim_xml['value'].replace('_output_', output_file_name)
                )
                xml_file.write(xml_string)

            # Execute job
            jar_name = '%s_%i_%i.run' % (user_id, job_id, self.gid[1])

            if not os.path.exists(jar_name):
                with open(jar_name, 'wb') as jar_file:
                    jar = get_file(job_id, user_id)
                    jar_file.write(jar)

            self.status = 'executing'

            process = Popen(['java', '-jar', jar_name, xml_name], stdout=PIPE)
            while process.poll() == None:
                self.status = process.stdout.read(256)
                print self.status,

            self.status = 'posting results'

            print 'Setting up file transfer...'
            sock = socket(AF_INET, SOCK_STREAM)
            local_ip = get_local_ip()
            sock.bind((local_ip, 0))
            sock.listen(1)

            # send back result
            with open(output_file_name, 'r') as result_file:
                result = result_file.read().encode('zlib').encode('base64')

            self.mgr.broadcast_message(ResultMessage(msg={
                'job_id': sim['job_id'],
                'sim_id': sim['sim_id'],
                'user_id': sim['user_id'],
                'sample': sim['sample'],
                'socket': sock.getsockname(),
                'm_size': len(result),
            }))

            print 'Transferring result file on', sock.getsockname()
            s,a = sock.accept()
            sendall(s, result)
            sock.close()

            os.remove(xml_name)
            os.remove(output_file_name)
            print
        except Exception, e:
            print 'Worker error: ', e
            print 'Stack trace:'
            print_exc(file=sys.stdout)
            print

            #TODO: maybe send an error message to replenish the lost sample
            self.mgr.broadcast_message(JobErrorMessage(msg={
                'job_id': sim['job_id'],
                'sim_id': sim['sim_id'],
                'user_id':sim['user_id']
            }))

        return True


    def handle_RmJarMessage(self, msg):
        jar_file = msg.get_property('msg')['jar_file']

        if os.path.exists(jar_file):
            os.remove(jar_file)

        return False


    def handle_NoJobMessage(self, msg):
        time.sleep(1)
        self.mgr.broadcast_message(JobRequestMessage(msg={'allowed_users':ALLOWED_USERS}))
        self.status = 'request resent'

        return True


    def handle_AckResultMessage(self, msg):
        print 'ACK received'
        self.mgr.broadcast_message(JobRequestMessage(msg={'allowed_users':ALLOWED_USERS}))
        self.status = 'ACK Received...'

        return True


    def handle_KillMessage(self, msg):
        self.running = False
        return True


    def run(self):
        while self.running:
            try:
                self.mgr.tick()
            except KeyboardInterrupt, SystemExit:
                self.running = False

if __name__ == '__main__':
    Worker().run()

