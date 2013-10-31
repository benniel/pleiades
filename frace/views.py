from django.conf import settings
from django.template import Context, RequestContext, loader
from django.http import HttpResponse, HttpResponseRedirect
from django import forms
from pleiades import pleiades_settings
from frace import parameters, frace, utils
import pleiades.views as pleiades
import os, re, shutil, string

JAR_CHOICES = (('Custom','Custom Jar File'),
               ('Master', 'Current Master Branch (' + pleiades_settings.cilib_snapshot_version + ')')
              )

class FraceForm(forms.Form):
    job_name = forms.CharField(max_length=100, required=True)
    algorithm_input_file = forms.FileField(required=True)
    problems_input_file = forms.FileField(required=True)
    jar_options = forms.ChoiceField(widget=forms.RadioSelect(attrs={'onclick':'if (this.value != "Custom"){document.getElementById("id_custom_jar_file").disabled=1} else {document.getElementById("id_custom_jar_file").disabled=0}'}),
                                    choices=JAR_CHOICES, required=True, initial='Master')
    custom_jar_file = forms.FileField(required=False)
    custom_jar_file.widget.attrs['disabled'] = True
    min_solutions = forms.ChoiceField(label='Minimum solutions',
        choices=[(2, 2), (5, 5), (10, 10), (15, 15)]
    )
    min_problems = forms.ChoiceField(label='Minimum problems',
        choices=[(5, 5), (10, 10), (15, 15), (90, 90)]
    )
    num_configurations = forms.ChoiceField(label='Configurations per race',
        choices=[(10, 10), (32, 32), (50, 50), (100, 100), (150, 150)]
    )
    iterations = forms.ChoiceField(
        choices=[(30, 30), (50, 50), (100, 100), (150, 150), (900, 900)]
    )
    samples = forms.ChoiceField(label='Compare average results over n samples',
        choices=[(1, 1), (10, 10), (50, 50), (100, 100), (150, 150)]
    )
    measurement = forms.CharField(label='Cilib measurement class path', max_length=200, required=True)
    iterated_frace = forms.BooleanField(label="Periodically regenerate parameter configurations (Iterated F-Race)", required=False, initial=True)
    interval = forms.ChoiceField(
        choices=[(5, 5), (10, 10), (15, 15), (180, 180)]
    )

class FraceBoundsForm(forms.Form):
    initial_sampling_method = forms.ChoiceField(
        choices=[('sobol', 'Sobol sequence'),
            ('uniform', 'Uniform per dimension'),
            #('custom', 'Custom configurations')
        ]
    )

def index(request):
    if 'username' in request.session:
        user = request.session['username']
    else:
        user = ''

    template = loader.get_template('frace/index.html')
    c = RequestContext(request, {
        'current': 'F-Race',
        'username': user,
    })
    return HttpResponse(template.render(c))

def new_job(request):
    if not 'username' in request.session:
        return HttpResponseRedirect('/pleiades/login/')
    else:
        user = request.session['username']

    header = 'New F-Race Job'

    if request.method == 'POST':
        form = FraceForm(request.POST, request.FILES)

        if form.is_valid():
            jar_option = form.cleaned_data['jar_options']
            job_name = form.cleaned_data['job_name']

            upload_path = os.path.join(settings.USER_DIRS, 'frace', user + '_uploads')
            base_path = os.path.join(settings.RESULTS_DIRS, 'frace')
            pleiades_results_path = os.path.join(base_path, user + '_' + job_name)
            frace_results_path = os.path.join(base_path, user)
    
            if not os.path.exists(upload_path):
                os.makedirs(upload_path)

            if not os.path.exists(frace_results_path):
                os.makedirs(frace_results_path)

            if os.path.exists(pleiades_results_path):
                shutil.rmtree(pleiades_results_path)

            os.makedirs(pleiades_results_path)
    
            algorithm_path = os.path.join(upload_path, job_name + '_frace-alg.scala')
            problems_path = os.path.join(upload_path, job_name + '_frace-prob.scala')
            jar_path = os.path.join(upload_path, job_name + '_frace.jar')
    
            pleiades.handle_uploaded_file(request.FILES['algorithm_input_file'], algorithm_path)
            pleiades.handle_uploaded_file(request.FILES['problems_input_file'], problems_path)

            if jar_option == 'Custom':
                pleiades.handle_uploaded_file(request.FILES['custom_jar_file'], jar_path)
                request.session['jar_type'] = 'custom'
                request.session['jar_path'] = jar_path
            elif jar_option == 'Master':
                request.session['jar_path'] = pleiades_settings.cilib_master_path
                request.session['jar_type'] = 'master'

            is_iterative = bool(form.cleaned_data['iterated_frace'])
            request.session['min_solutions'] = int(form.cleaned_data['min_solutions'])
            request.session['min_problems'] = int(form.cleaned_data['min_problems'])
            request.session['configurations'] = int(form.cleaned_data['num_configurations'])
            regen = 'regen_minmax_sobol(' + str(request.session['configurations']) + ')'
            samples = int(form.cleaned_data['samples'])
            interval = int(form.cleaned_data['interval'])
            measurement = form.cleaned_data['measurement']
            request.session['iterations'] = int(form.cleaned_data['iterations'])

            algorithm = utils.get_algorithm_string(algorithm_path)
            problems = utils.get_problem_strings(problems_path)

            #generator = parameters.initial_sobol(parameter_bounds, configurations)

            request.session['user_settings'] = frace.UserSettings(user, job_name)
            request.session['location_settings'] = frace.LocationSettings(base_path, pleiades_results_path)
            request.session['ifrace_settings'] = frace.IFraceSettings(is_iterative, interval, regen)
            request.session['simulation'] = frace.SimulationSettings(algorithm, problems, measurement, samples)

            request.method = ""

            print "woohoo!"

            return upload(request)
    else:
        form = FraceForm()
    
    template = loader.get_template('frace/form.html')

    c = RequestContext(request, {
        'current': 'F-Race',
        'form': form,
        'submit': 'Next',
        'back': 'Back',
        'header': header,
        'action': '/frace/new/',
        'username': user,
    })

    return HttpResponse(template.render(c))

def upload(request):
    if not 'username' in request.session:
        return HttpResponseRedirect('/pleiades/login/')
    else:
        user = request.session['username']

    header = 'Specify Initial Parameter Bounds'

    simulation = request.session['simulation']

    if request.method == 'POST':
        form = FraceBoundsForm(request.POST, request.FILES)
        num_params = add_parameters(form, simulation)

        if form.is_valid():
            gen = form.cleaned_data['initial_sampling_method']

            bounds = []

            for i in range(0, num_params):
                bounds.append(form.cleaned_data['parameter_' + str(i)])

            print bounds

            bounds_regex = r'\([0-9]+(?:\.[0-9]+)?(?:,)[0-9]+(?:\.[0-9]+)?\)'
            if all(re.match(bounds_regex, b) for b in bounds):
                bounds = [[float(j) for j in i[1:-1].split(',')] for i in bounds]
                print bounds
                if gen == 'sobol':
                    generator = parameters.initial_sobol(bounds, request.session['configurations'])
                elif gen == 'uniform':
                    pass

                print "success"
                frace_settings = frace.FRaceSettings(generator, request.session['min_problems'], request.session['min_solutions'], 0.05, request.session['iterations'])
                frace.runner(request, frace_settings)
            else:
                header = "Invalid Bounds"
    else:
        form = FraceBoundsForm()
        add_parameters(form, simulation)
    
    template = loader.get_template('frace/form.html')

    c = RequestContext(request, {
        'current': 'F-Race',
        'algorithm':simulation.algorithm.replace('>', '>\n'),
        'problems':(p.replace('>', '>\n') for p in simulation.problems),
        'form': form,
        'submit': 'Upload Job',
        'header': header,
        'action': '/frace/upload/',
        'username': user,
    })

    return HttpResponse(template.render(c))

def add_parameters(form, simulation):
    all_parameters = re.findall(r'class=\"tuning.TuningControlParameter\" index=\"\d+\"', simulation.algorithm)
    parameters = []
    
    for p in all_parameters:
        all = string.maketrans('','')
        index = all.translate(all, string.digits)
        p_index = p.translate(all, index)
        
        if not p_index in parameters:
            parameters.append(p_index)

    for i in range(0, len(parameters)):
            form.fields['parameter_' + str(i)] = forms.CharField(max_length=100, required=True)

    return len(parameters)
