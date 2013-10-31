import os
import re
import subprocess

from utils import *

header_section = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE simulator [
<!ATTLIST algorithm id ID #IMPLIED>
<!ATTLIST problem id ID #IMPLIED>
<!ATTLIST measurements id ID #IMPLIED>
]>
<simulator>
'''

simulation = '''
<simulation samples="$4">
    <algorithm idref="$1"/>
    <problem idref="$2"/>
    <measurements idref="m_frace" />
    <output format="TXT" file="$3"/>
</simulation>
'''

def generate_script(pars, iteration, simulation_settings, user_settings, location_settings):
    '''
    Function to generate a script file given certain elements
    NOTE: Change this as needed
    '''

    script_filename = os.path.join(location_settings.base_location, user_settings.user, user_settings.user + '_' + user_settings.job + '.xml')
    script = open(script_filename, 'w')

    problem = simulation_settings.problems.pop(0)
    simulation_settings.problems.append(problem)

    algorithms = ''
    sims = ''

    # TODO: it kinda sucks doing it this way, jython? :)
    for p in pars:
        algorithm = simulation_settings.algorithm

        config = par_to_num(p)
        for i in range(len(config)):
            algorithm = algorithm.replace(
                'tuning.TuningControlParameter" index="%d"' % i, 
                'controlparameter.ConstantControlParameter" parameter="%f"' % config[i]
            )

        algorithms += algorithm.replace(id_name(simulation_settings.algorithm), 'alg_' + p, 1) + '\n'

        sims += simulation.replace('$1', 'alg_' + p).replace('$2', id_name(problem)) \
            .replace('$3', parameter_filename(user_settings.user, user_settings.job, iteration, p, location_settings.base_location)) \
            .replace('$4', str(simulation_settings.samples))

    script.write(header_section + '\n')
    script.write('<measurements id="m_frace" class="simulator.MeasurementSuite" resolution="5000">\n' + \
                 simulation_settings.measurement + '</measurements>\n')
    script.write('<problems>' + problem + '</problems>\n')
    script.write('<algorithms>' + algorithms + '</algorithms>\n')
    script.write('<simulations>' + sims + '</simulations>')
    script.write('</simulator>')

    script.close()
    return script_filename


def run_script(script, jar_path, jar_type):
    '''
    Function to run a given script
    '''

    subprocess.call(['java', '-Dhazelcast.logging.type=none', '-jar', './Pleiades', '-u', 'frace', '-i', script, '-j', jar_path, '-t', jar_type])
