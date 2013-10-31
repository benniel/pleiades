import os
import re

from numpy import array
from scipy.stats.mstats import rankdata

def par_to_num(a):
    '''
    Converts a parameter configuration string into a list of numbers
    '''

    return [float(i) for i in a.replace('___', '.').split('_')]


def par_to_result(a):
    return '[' + a.replace('___', '.').replace('_', ',') + ']'


def parameter_name(p):
    '''
    Converts a list of numbers into a parameter configuration string
    '''

    return '_'.join([str(j) for j in p]).replace('.', '___')


def parameter_filename(user, job, iteration, p, base_location):
    '''
    Creates a file path for a given parameter/iteration combination
    '''
    print base_location
    print user
    print job
    print str(iteration) + '_' + p + '.txt'
    return os.path.join(str(iteration) + '_' + p + '.txt')


def parameter_filenames(user_settings, iteration, pars, location_settings):
    '''
    Creates a list of file paths for the given parameters
    '''

    return [ os.path.join(location_settings.base_location, user_settings.user + '_' + user_settings.job, str(iteration) + '_' + p + '.txt') for p in pars ]


def sort_pars(results, pars):
    '''
    Sorts the parameters in order of performance according to results
    '''
    return [i[1] for i in sorted(zip(list(sum(rankdata(array(results), axis=1))),pars))]


def def_name(s):
    '''
    Retrieves the scala def name for a given scala def
    '''

    return re.search(r'def(.*)=', s).group(1).strip()


def id_name(s):
    return re.search(r'id="(.*)" class', s).group(1).strip()


def get_algorithm_string(algorithm_path):
    with open(algorithm_path) as f:
        s = f.read().replace('\n', '')

    print "here"
    return re.findall(r'<algorithm\s.*</algorithm>', s)[0]

def get_problem_strings(problems_path):
    with open(problems_path) as f:
        s = f.read().replace('\n', '')

    return [i + '</problem>' for i in re.findall(r'<problem\s.*</problem>', s)[0].split('</problem>')[:-1]]
