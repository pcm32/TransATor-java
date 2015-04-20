package uk.ac.ebi.cheminformatics.pks.generator;

import org.apache.log4j.Logger;
import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IBond;
import org.openscience.cdk.interfaces.IPseudoAtom;
import uk.ac.ebi.cheminformatics.pks.monomer.MonomerProcessor;
import uk.ac.ebi.cheminformatics.pks.monomer.MonomerProcessorFactory;
import uk.ac.ebi.cheminformatics.pks.monomer.PKMonomer;
import uk.ac.ebi.cheminformatics.pks.sequence.feature.KSDomainSeqFeature;
import uk.ac.ebi.cheminformatics.pks.sequence.feature.SequenceFeature;

import java.util.LinkedList;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: pmoreno
 * Date: 3/7/13
 * Time: 21:37
 * To change this template use File | Settings | File Templates.
 */
public class PKSAssembler {

    private static final Logger LOGGER = Logger.getLogger(PKSAssembler.class);

    private PKStructure structure;

    private List<SequenceFeature> toBePostProcessed;
    private List<SequenceFeature> subFeaturesForNextKS;

    public PKSAssembler() {
        this.structure = new PKStructure();
        this.toBePostProcessed = new LinkedList<SequenceFeature>();
        this.subFeaturesForNextKS = new LinkedList<>();
    }

    /**
     * Given a sequenceFeature, it adds the next monomer to the PKS structure. The monomer is obtained from the sequence
     * feature. According to the sub-features found upstream, modifications can be exerted on the monomer.
     *
     * @param sequenceFeature
     */
    public void addMonomer(SequenceFeature sequenceFeature) {
        if(!sequenceFeature.getClass().isInstance(KSDomainSeqFeature.class)) {
            this.subFeaturesForNextKS.add(sequenceFeature);
            return;
        }
        // From here, we are only looking at KS domains seq features.
        //LOGGER.info("Adding monomer " + sequenceFeature.getName());
        if(sequenceFeature.getMonomer().getMolecule().getAtomCount()==0) {
            // empty molecule for advancing only
            return;
        }

        processSubFeatures(sequenceFeature.getMonomer());

        if(structure.getMonomerCount()==0) {
            // Starting nascent polyketide
            structure.add(sequenceFeature.getMonomer());
            checkNumberOfConnectedComponents(sequenceFeature);
        }
        else if(structure.getMonomerCount()==1 && sequenceFeature.getMonomer().isNonElongating()) {
            /*
               if the chain only has one monomer currently and the new sequence feature
               represents a non-elongating clade, then we simply remove what it is there and
               add the monomer of the non-elongating clade (which represents the transformation
               done to the previously existing monomer).
             */
            structure.getMolecule().removeAllElements();
            structure.add(sequenceFeature.getMonomer());
            checkNumberOfConnectedComponents(sequenceFeature);
        }
        else
        {
            // For extending clades (where no monomer should be added)
            // we need to remove the previous monomer and enact the equivalent
            // to the transformation provided.
            if(sequenceFeature.getMonomer().isNonElongating()) {
                structure.removeLastMonomer();
            }

            IAtom connectionAtomInChain = structure.getConnectionAtom();
            IBond connectioBondInMonomer = sequenceFeature.getMonomer().getConnectionBond();

            IAtomContainer structureMol = structure.getMolecule();

            int order = removeGenericConnection(connectionAtomInChain, structureMol);
            int orderNew = connectioBondInMonomer.getOrder().ordinal();

            int hydrogensToAdd = order - orderNew;

            IAtomContainer monomer = sequenceFeature.getMonomer().getMolecule();
            if(monomer.getAtomCount()>0) {
                int indexToRemove = connectioBondInMonomer.getAtom(0) instanceof IPseudoAtom ? 0 : 1;

                monomer.removeAtom(connectioBondInMonomer.getAtom(indexToRemove));
                connectioBondInMonomer.setAtom(connectionAtomInChain,indexToRemove);

                structure.add(sequenceFeature.getMonomer());

                // adjust implicit hydrogens
                for (int i=0;i<Math.abs(hydrogensToAdd);i++) {
                    int current = connectionAtomInChain.getImplicitHydrogenCount();
                    connectionAtomInChain.setImplicitHydrogenCount(current+1*Integer.signum(hydrogensToAdd));
                }
            }
            checkNumberOfConnectedComponents(sequenceFeature);

            // here we do post processing specific to the particular clade just added
            if(sequenceFeature.hasPostProcessor()) {
                toBePostProcessed.add(sequenceFeature);
            }
        }
    }

    /**
     * Deals with all the modifications that different domains upstream of the current KS
     * exert to the monomer added by this current KS.
     */
    private void processSubFeatures(PKMonomer monomer) {
        for(SequenceFeature feat : subFeaturesForNextKS) {
            MonomerProcessor processor = MonomerProcessorFactory.getMonomerProcessor(feat);
            processor.modify(monomer);
        }
        subFeaturesForNextKS.clear();
    }

    private void checkNumberOfConnectedComponents(SequenceFeature feature) {
        IAtomContainer mol = structure.getMolecule();
        if(!ConnectivityChecker.isConnected(mol)) {
            LOGGER.error("Newest feature "+feature.getName()+" produced disconnection");
        }
    }

    public void postProcess() {
        for (SequenceFeature toPP : this.toBePostProcessed) {
            PostProcessor proc = toPP.getPostProcessor();
            proc.process(structure,toPP.getMonomer());
        }
    }


    /**
     * Removes the generic atom connected to the connectionAtomInChain, and the bond connecting them. Number of
     * hydrogens connected to the connectionAtomInChain is not modified. The order of the bond removed is obtained.
     *
     * @param connectionAtomInChain
     * @param structureMol
     * @return order of the bond removed.
     */
    private int removeGenericConnection(IAtom connectionAtomInChain, IAtomContainer structureMol) {
        IAtom toRemoveA=null;
        for (IBond connected : structureMol.getConnectedBondsList(connectionAtomInChain)) {
            for(IAtom atomCon : connected.atoms()) {
               if(atomCon.equals(connectionAtomInChain))
                   continue;
               if(atomCon instanceof IPseudoAtom && ((IPseudoAtom)atomCon).getLabel().equals("R2")) {
                   toRemoveA = atomCon;
                   break;
               }
            }
        }
        int order = 0;
        if(toRemoveA!=null) {
            order = structureMol.getBond(connectionAtomInChain,toRemoveA).getOrder().ordinal();
            structureMol.removeBond(connectionAtomInChain,toRemoveA);
            structureMol.removeAtom(toRemoveA);
        }
        return order;
    }

    public PKStructure getStructure() {
        return structure;
    }

}
