tencil TUNE - README ==================
 
  + Creation (Biagio), July 24, 2015
  + Added SVM Rank (Biagio), September 2015
  + Updating configuration information (Biagio), March 2016
  + Cleaninig to ease PPoPP artifact evaluation (Biagio), July 2016

Installation information for the Stencil Tune framework. 
Before to start, please read the paper:

This code is available at the svn repository: https://svn.aes.tu-berlin.de/svn/research/projects/stencil_tune

Autotuning Stencil Computations with Structral Ordinal Regression Learning
Biagio Cosenza, Juan J. Durillo, Stefano Ermon, Ben Juurlink
XXX


 
The project includes a set of bash scripts to reproduce the setup used in the paper. They have been tested on a 64-bit Ubuntu linux machine with Xeon E5 (see the paper for details), with Hyperthreading disabled and PowerBoost enabled. XXX

It is very important to know that, as pointed out in the paper, the preprocessing phase required to install the StencilTune on a new machine takes at most 2 days, due to the long compilation time required by Patus for multi-versioning support. However, if you have the same processor type of the one used in the paper, you can quickly setup the system by using our pre-built model, therefore creating a fully working installation in few minutes.

Therefore, we distinguish two installation scenarios:
1. your target hw is the same of the one proposed in the paper, then you should:
  Install Patus 
  Install SVM Rank
  Install Stencil TUNE with a prebuilt model  

2. you have a different target hw from the one in the paper, tjen you shuold:
  Install Patus 
  Install SVM Rank  
  Install Stencil TUNE with a new model

The paper also shows results for search based heuristics, used for comparison. If you want to run those, go on the 'Iterative-Search' part.


Should you have any question or about the installation process, please mail to cosenza (at) tu-berlin.de


================== Install PATUS ==================

1) Downloading & Installing PATUS

Requirements:
 - gcc and make 
 - Maxima
  > sudo apt-get install maxima
 
 - Patus stencil compiler 
  The script get_patus.sh will download the latest version
  > source get_patus.sh 
  
 - Getting Oracle VM (highly recommended, as the dafault OpenJDK Runtime Environment is very slow!) 
  > sudo apt-add-repository ppa:webupd8team/java
  > sudo apt-get update
  > sudo apt-get install oracle-java8-installer
  (if not done yet, you should additionally set JAVA_HOME with something like: export JAVA_HOME=/usr/lib/jvm/java-8-oracle 
  and add to PATH /usr/lib/jvm/java-8-oracle/bin. To be sure you are really using the Oracle JVM with check: java -version)

Optional:
 - Getting Eclipse (for development) 
  > sudo apt-get install eclipse eclipse-cdt g++
 - GNUplot (used by some Patus's automatic tuning script) 
  > sudo apt-get install gnuplot
 - Python and Pythons's required libraries (used by StencilTUNE for scripting and plotting)
  > sudo apt-get install python-numpy python-scipy python-matplotlib ipython ipython-notebook python-pandas python-sympy python-nose


2) Configure PATUS

Run the script configure_patus.sh:
  > source configure_patus.sh
 
 
3) Getting started with PATUS

All scripts starting with the prefix 'patus' allow to test the Patus stencil compiler with default params. 
For example, you can build and run all the available test benchmarks with the following command (takes about 1h):
  > bash ./patus_all.sh


 ================== Install SVM Rank ==================
 
1) Downloading & Installing SVM Rank from Cornell's repository
  > bash ./get_svmrank.sh

 
================== Install Stencil TUNE with a new model ==================
 
There are a collection of scripts to build the model and run the TUNE Optimizer

1) [2 sec] Compile the code generator and auto-generate stencil training code patterns 
 > bash ml_1_autogen_stencil.sh

This step will generate  a number of stencil code in 'temp/ml_stencil_code'.


2*) [24-32 hours] (Double) compilation of the training stencil codes with Patus and back-end compiler (e.g., gcc). Some automatically generated codes using many samples (e.g., hypercubes) are very slow to compile with Patus. Unfortunately, we couldn't easily fix this issue, thus compilation will take approximately 32 hours on a Xeon E5 (see Table II, "e5" in the paper, for a detailed description). 

You can generate and build the code and scripts for a new model, from scratch, (warning: it will take approx. 32 hours!) with:
 > bash ml_2_autocompile.sh

After this step, 'temp/ml_stencil_build' will contain the compiled, multi-versioned binary sources.


3*) [from minutes to hours] Run all stencil codes  with a set of tuning parameters, and save results in a ranked/qid format. This step is highly parametrized: you can quickly create small dataset with few dozens of run per kernel, or, instead, use a much bigger dataset. E.g., a quick and small dataset can be generated in this way: 
 > bash ml_3_stencil_exec.sh small

Running time of the stencil executions and corresponding encoded values are stored in a number of folder in temp (ml_execution_params, ml_result_raw, ml_result_qid, ml_result_sorted). 
 
Supported dataset-size flag are: "small", "medium", "big", and numerical, such as "10". We suggest to first test the system with the smallest dataset possible, as the bigger the size, the longer the time to generate it (generation goes from minutes to hours or even days!). The 'small' option produces a dataset with 3200 samples.
For an estimation of the stencil execution time for different datasets, have a look at the paper, Table II.


4) [<1 min] Prepare all the collected data for the training phase, using the SVM Rank data format.
 > bash ml_4_prepare_training.sh
 
 This step will create an output file in temp named training_size_SX.txt, where SX is the size of the training set.
 This script will also try to run a python script calculate the Tau distribution of the training dataset. However, this will not work if called directly in this way; to compute this additional information have a look at the scripts 'ml_learning_curve.sh', which sets the right configuration for calculating the Tau distribution.

 
5) [few seconds] Model training using SVM Rank
 > ./svm_rank/svm_rank_learn -c 20.0 ./temp/training_size_*.txt data/model.dat

 
6) [<1 ms] Regression with a test code from the Patus examples (in this case, blur 1024x1024)
 > ./svm_rank/svm_rank_classify  data/blur_1024_1024.test data/model.dat output.txt
 Successively, with
 > cat output.txt
 you can see the score (a metric for the ordinal distance) for each configutaion in the data/blur_1024_1024.test file.
 The best performing configuration is the one with the smallest score (e.g., line 30), corresponding to the 30 configuraion in the .test file.
 

================== Install Stencil TUNE with a prebuilt model ==================

Same as above, but replace step (2) with the following:

2) If you have a Xeon E5, you can just quickly download our build (depending on your network, about 30 sec)
 > bash ml_2_get_xeon_build.sh


================== Replicate the results in the paper  ==================
 
A number of scripts have been created to reproduce the same test benchmarks you find in the paper.
Data for Figure 4, 5, 6 and 7 have been mainly can be generated by these two scripts:


[]
> bash learning_curve.sh
Create different datasets with different sizes and same parameters. 

[<20 seconds for each dataset]
> bash ml_5_evaluate_all.sh
For each dataset, builds a model with the same parameters  (C=0.01 and linear kernel), does regression and executes the top-ranked configuration.
If more datasets are available, this step takes longer teme, as it is repeated for each file in: temp/training*.txt.



Comparison with iterative search heuristics is discussed in the next section.
 
A number of python scripts have been used to plot results, copmute statistical distribution and show copmarison between different methods. They are all available in the /source folder (we suggest to copy the txt files on which such scripts work on a different folder).
 
plot_optimize_all.sh
plot_search_comparison.sh
 
 
================== Iterative Search ==================
  
1) be sure to have already configured Patus correctly (including the configure script listed above)
 
2) compile all Patus DSLs
 > bash patus_all.sh
 
3) run the iterative optimizer
 > bash optimize_all.sh
 
 
